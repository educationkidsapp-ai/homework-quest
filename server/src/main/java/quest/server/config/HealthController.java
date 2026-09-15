package quest.server.config;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** `GET /health` — the plain probe the app, Docker and Cloud Run use (actuator has the detailed one). */
@RestController
@Tag(name = "Health", description = "Liveness and version")
public class HealthController {
    private final QuestProperties props;
    public HealthController(QuestProperties props) { this.props = props; }
    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "ok", "version", props.version() == null ? "dev" : props.version()); }
}
