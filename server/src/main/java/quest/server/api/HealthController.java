package quest.server.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import quest.server.api.dto.Requests;

@RestController
public class HealthController {
    private final String version;
    public HealthController(@Value("${quest.version:0.1.0}") String version) { this.version = version; }

    @GetMapping("/health")
    public Requests.Health health() { return new Requests.Health("ok", version); }
}
