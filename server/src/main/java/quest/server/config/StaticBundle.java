package quest.server.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One single-page-app bundle on the filesystem, served from a URL prefix. Shared by {@link AdminPanelController}
 * (`/panel/`, the webpack bundle) and {@link DashboardController} (`/dashboard/`, the Angular bundle): the two differ
 * only in which file names they consider content-hashed, the MIME table and the headers they add, so the part that
 * must never differ — where a request path is allowed to land — lives here once.
 *
 * <p>A request may only ever land inside the bundle, and that is checked twice because one check is not enough.
 * {@link Path#normalize()} is purely lexical, so it settles `..` and absolute paths but says nothing about a symlink;
 * {@link Path#toRealPath} resolves every link and gives the file's true location, so a link planted inside the bundle
 * that points at `/etc/passwd` is refused too. Both the root and the candidate are resolved the same way before they
 * are compared — on macOS the root itself is usually reached through a link (`/var` → `/private/var`), so comparing a
 * real candidate against a merely normalised root would reject everything.
 */
final class StaticBundle {
    /** The file a request resolved to, as its real path; `asset` is false when the SPA shell was substituted. */
    record Resolved(Path file, boolean asset, String rel) {}

    private final Path dir;
    private final String prefix;

    /** `configured` is the directory (blank = no bundle, every request 404s); `prefix` the URL segment, e.g. `panel`. */
    StaticBundle(String configured, String prefix) {
        this.dir = configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath().normalize();
        this.prefix = prefix;
    }

    /** The configured directory, or null when nothing is configured — only for the startup log line. */
    Path dir() { return dir; }

    boolean missing() { return dir == null || !Files.isDirectory(dir); }

    /**
     * The file to serve, or null for a 404.
     *
     * <p>Only an extension-less path falls back to the shell. Both SPAs route in memory, so `/dashboard/admin/schools`
     * must return `index.html` — but `/dashboard/main-DEADBEEF.js` must not. A browser holding a stale `index.html`
     * across a deploy asks for a hashed chunk that no longer exists, and answering that with `200 text/html` turns a
     * plain 404 — which a reload-on-chunk-error handler can act on — into an opaque "failed to fetch dynamically
     * imported module". The cost of the rule is that a client route whose last segment contains a dot would 404;
     * neither dashboard has one, and a route that needs one can be given a trailing slash.
     */
    Resolved resolve(HttpServletRequest request) {
        Path root = real(dir);
        if (root == null || !Files.isDirectory(root)) return null;
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rel = path == null ? "" : path.replaceFirst("^/" + prefix + "/?", "");
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root)) return null;                    // lexical: `..` and absolute paths, before touching the disk
        boolean asset = rel.substring(rel.lastIndexOf('/') + 1).indexOf('.') >= 0;
        if (asset) { if (!Files.isRegularFile(file)) return null; } // a missing asset is a 404, never the shell
        else file = root.resolve("index.html");
        if (!Files.isRegularFile(file)) return null;
        Path real = real(file);
        if (real == null || !real.startsWith(root)) return null;    // symlinks: the file's true location must still be inside
        return new Resolved(real, asset, rel);
    }

    /** The path with every symlink resolved, or null when it does not exist or cannot be read. */
    private static Path real(Path path) {
        if (path == null) return null;
        try { return path.toRealPath(); } catch (IOException | SecurityException e) { return null; }
    }
}
