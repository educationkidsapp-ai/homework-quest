package quest.server.config;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * D10: the Angular dashboard is served by the API itself, from the same origin, under `/dashboard/` — `webAdmin`'s
 * Compose bundle keeps `/panel/` until P3.6 retires it ({@link AdminPanelController}). The Docker image puts the
 * bundle in DASHBOARD_DIR; with nothing configured (local runs, and every deploy until the image ships the bundle)
 * the route answers 404 and the reason is logged once at startup instead of per request.
 */
@RestController
@Hidden   // a static SPA bundle, not an API: `/dashboard/**` is no valid OpenAPI path template and the generated client must not see it
@Tag(name = "Dashboard", description = "The Angular dashboard bundle")
public class DashboardController {
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    /**
     * Angular 22's esbuild builder (`outputHashing: all`) names every emitted file `<name>-<hash>.<ext>` with an
     * 8-character base32 hash — a real `pnpm build --configuration=qa` of `dashboard/` produced `main-WQMMA2Q6.js`,
     * `chunk-2RKMGULP.js`, `styles-E4MV3RDG.css` and `media/archivo_variable-CP6HG7EB.woff2`. Those may be cached
     * forever. `index.html`, `favicon.ico` and everything Angular copies verbatim out of `public/` and `src/assets/`
     * (`assets/i18n/en.json`, `assets/fonts/*.woff2`) keep their names across deploys and must not be, which is why
     * `assets/` is excluded outright as well as by the pattern: a hand-written asset could otherwise be named like a
     * hash by accident and then be pinned in every browser that ever fetched it.
     *
     * <p>The hash segment is matched case-insensitively and with no lower length bound of 8 so that a future
     * `outputHashing` change (or a hex-hashed fixture) still lands in the immutable bucket rather than silently
     * losing its long cache.
     */
    private static final Pattern HASHED = Pattern.compile("^[A-Za-z0-9_-]+-[A-Za-z0-9]{8,}\\.(?:js|mjs|css|woff2?|ttf|otf|png|jpe?g|gif|webp|svg|ico|json)$");

    /**
     * Same-origin everything. `img-src` additionally allows `https:` and `data:` because a school logo is served from
     * GCS and the theme preview renders inline data URLs; `style-src` needs `'unsafe-inline'` for the CSS custom
     * properties Angular writes onto the document for school themes. Fonts are bundled (`media/*.woff2` above), so
     * neither `fonts.googleapis.com` nor `fonts.gstatic.com` is allowed — `default-src 'self'` covers `font-src`.
     *
     * <p>N4.2 gap: `media-src 'self' data:` is there for the Results page, which plays back the retell a child
     * recorded. The stored audio is same-origin (`/media/child/{id}`), which `'self'` covers; `data:` is for a clip
     * the page holds in memory rather than on the server — a preview, or a recording not yet uploaded. `media-src`
     * has to name `data:` itself because it inherits nothing from the `img-src` exception above, and without it the
     * player is silent with the reason only in the console, which is the shape of bug that is found in QA.
     */
    private static final String CSP = "default-src 'self'; img-src 'self' https: data:; media-src 'self' data:; style-src 'self' 'unsafe-inline'; connect-src 'self'";

    private final StaticBundle bundle;

    public DashboardController(QuestProperties props) {
        this.bundle = new StaticBundle(props.dashboardDir(), "dashboard");
        Path dir = bundle.dir();
        if (dir == null) log.info("DASHBOARD_DIR is not set: /dashboard/ answers 404 (the Docker image sets it; local runs use `pnpm start` in dashboard/)");
        else if (bundle.missing()) log.warn("DASHBOARD_DIR={} is not a directory: /dashboard/ answers 404", dir);
        else log.info("Serving the dashboard bundle at /dashboard/ from {}", dir);
    }

    @PreAuthorize("permitAll")
    @GetMapping({"/dashboard", "/dashboard/", "/dashboard/**"})
    public ResponseEntity<Resource> dashboard(HttpServletRequest request) {
        var resolved = bundle.resolve(request);
        if (resolved == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        String name = resolved.file().getFileName().toString();
        boolean immutable = resolved.asset() && !resolved.rel().startsWith("assets/") && HASHED.matcher(name).matches();
        CacheControl cache = immutable ? CacheControl.maxAge(365, TimeUnit.DAYS).immutable() : CacheControl.noCache();
        String csp = name.endsWith(".html") && !name.equals("index.html")
                ? "default-src 'self' 'unsafe-inline' 'unsafe-eval' https: data:; font-src 'self' https: data:; style-src 'self' 'unsafe-inline' https:; script-src 'self' 'unsafe-inline' 'unsafe-eval' https:; img-src 'self' https: data:; media-src 'self' data:; connect-src 'self'"
                : CSP;
        return ResponseEntity.ok()
                .contentType(mime(name))
                .cacheControl(cache)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", csp)
                .body(new FileSystemResource(resolved.file()));
    }

    /** Only what an Angular bundle actually emits; anything else is a download, not something the browser executes. */
    private static MediaType mime(String name) {
        int dot = name.lastIndexOf('.');
        return switch (dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)) {
            case "html" -> MediaType.TEXT_HTML;
            case "js", "mjs" -> MediaType.parseMediaType("text/javascript;charset=UTF-8");
            case "css" -> MediaType.parseMediaType("text/css;charset=UTF-8");
            case "json", "map" -> MediaType.APPLICATION_JSON;
            case "webmanifest" -> MediaType.parseMediaType("application/manifest+json");
            case "woff2" -> MediaType.parseMediaType("font/woff2");
            case "woff" -> MediaType.parseMediaType("font/woff");
            case "ttf" -> MediaType.parseMediaType("font/ttf");
            case "otf" -> MediaType.parseMediaType("font/otf");
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "ico" -> MediaType.parseMediaType("image/x-icon");
            case "txt" -> MediaType.parseMediaType("text/plain;charset=UTF-8");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
