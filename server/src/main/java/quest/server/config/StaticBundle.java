package quest.server.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One single-page-app bundle on the filesystem, served from a URL prefix. Shared by {@link AdminPanelController}
 * (`/panel/`, the webpack bundle) and {@link DashboardController} (`/dashboard/`, the Angular bundle): the two differ
 * only in which file names they consider content-hashed, the MIME table and the headers they add, so the part that
 * must never differ — where a request path is allowed to land — lives here once.
 *
 * <p>Traversal is impossible by construction: the request path is resolved under the root and normalised, and a file
 * that does not then still start with the root is refused. `/dashboard/../application.yml` therefore answers 404
 * whether or not the container normalised the URI first.
 */
final class StaticBundle {
    /** The file a request resolved to; `asset` is false when the SPA shell was substituted for an unknown route. */
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
     * The file to serve, or null for a 404. A path with no file behind it is the app shell (`index.html`): both SPAs
     * route in memory, so `/dashboard/admin/schools` must return the shell rather than a 404.
     */
    Resolved resolve(HttpServletRequest request) {
        if (missing()) return null;
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rel = path == null ? "" : path.replaceFirst("^/" + prefix + "/?", "");
        Path file = dir.resolve(rel).normalize();
        if (!file.startsWith(dir)) return null;
        boolean asset = !rel.isEmpty() && Files.isRegularFile(file);
        if (!asset) file = dir.resolve("index.html");
        if (!Files.isRegularFile(file)) return null;
        return new Resolved(file, asset, rel);
    }
}
