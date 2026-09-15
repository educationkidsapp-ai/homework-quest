package quest.server.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Serves the admin panel (the Compose-for-Web bundle) from the same origin as the API, under /panel/.
 * The Docker image puts the bundle in PANEL_DIR; with no bundle (local runs) the route answers 404.
 * Assets are content-hashed by webpack, so they get a long cache; index.html never does.
 */
@RestController
public class AdminPanelController {
    private final Path dir;

    public AdminPanelController(@Value("${quest.panel-dir:}") String panelDir) {
        this.dir = panelDir == null || panelDir.isBlank() ? null : Path.of(panelDir).toAbsolutePath().normalize();
    }

    @GetMapping({"/panel", "/panel/", "/panel/**"})
    public ResponseEntity<Resource> panel(HttpServletRequest request) {
        if (dir == null || !Files.isDirectory(dir)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        String path = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rel = path == null ? "" : path.replaceFirst("^/panel/?", "");
        Path file = dir.resolve(rel).normalize();
        if (!file.startsWith(dir)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        boolean asset = !rel.isEmpty() && Files.isRegularFile(file);
        if (!asset) file = dir.resolve("index.html"); // the panel routes in memory; any other path is the app shell
        if (!Files.isRegularFile(file)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
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
