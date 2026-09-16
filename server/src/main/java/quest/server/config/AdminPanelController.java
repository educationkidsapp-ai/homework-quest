package quest.server.config;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
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

/**
 * Serves the admin panel (the Compose-for-Web bundle) from the same origin as the API, under /panel/.
 * The Docker image puts the bundle in PANEL_DIR; with no bundle (local runs) the route answers 404.
 * Assets are content-hashed by webpack, so they get a long cache; index.html never does.
 * The path resolution itself lives in {@link StaticBundle}, shared with {@link DashboardController} (`/dashboard/`).
 */
@RestController
@Hidden   // a static SPA bundle, not an API: `/panel/**` is no valid OpenAPI path template and the generated client must not see it
@Tag(name = "Panel", description = "The admin panel bundle")
public class AdminPanelController {
    private final StaticBundle bundle;

    public AdminPanelController(@Value("${quest.panel-dir:}") String panelDir) {
        this.bundle = new StaticBundle(panelDir, "panel");
    }

    @PreAuthorize("permitAll")
    @GetMapping({"/panel", "/panel/", "/panel/**"})
    public ResponseEntity<Resource> panel(HttpServletRequest request) {
        var resolved = bundle.resolve(request);   // the panel routes in memory; any path with no file behind it is the app shell
        if (resolved == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        Path file = resolved.file();
        boolean asset = resolved.asset();
        String name = file.getFileName().toString();
        MediaType type = name.endsWith(".wasm") ? MediaType.parseMediaType("application/wasm")
                : name.endsWith(".js") ? MediaType.parseMediaType("text/javascript;charset=UTF-8")
                : name.endsWith(".html") ? MediaType.TEXT_HTML
                : name.endsWith(".ttf") ? MediaType.parseMediaType("font/ttf")
                : MediaType.APPLICATION_OCTET_STREAM;
        // only content-hashed files (e.g. 8bc1b48ee28fd6b51bb9.wasm) may be cached forever; admin.js / index.html / fonts keep their names across deploys
        boolean hashed = name.matches("^[0-9a-f]{16,}\\.[a-z0-9]+$");
        CacheControl cache = asset && hashed ? CacheControl.maxAge(365, TimeUnit.DAYS).immutable() : CacheControl.noCache();
        return ResponseEntity.ok().contentType(type).cacheControl(cache).body(new FileSystemResource(file));
    }
}
