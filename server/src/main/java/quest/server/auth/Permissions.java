package quest.server.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * `permissions.json`: the permission → roles matrix and the endpoint → permission table. Every endpoint the server
 * serves has an entry (`PermissionsTest` fails otherwise); `@PreAuthorize` reads the matrix from P1.3 onwards, and
 * `GET /me/permissions` serves the keys a role holds.
 */
@Component
public class Permissions {
    /** `PUBLIC` needs no token; `PARENT` is a Firebase-authenticated parent; the rest are dashboard roles. */
    public static final String PUBLIC = "PUBLIC";
    public static final String PARENT = "PARENT";

    public record Endpoint(String method, String path, String permission) {}
    private record Document(Map<String, List<String>> permissions, List<Endpoint> endpoints) {}

    private final Map<String, List<String>> matrix;
    private final List<Endpoint> endpoints;
    private final Map<String, Endpoint> byRoute;

    public Permissions(ObjectMapper mapper) {
        Document doc;
        try (InputStream in = new ClassPathResource("permissions.json").getInputStream()) {
            doc = mapper.readValue(in, new TypeReference<Document>() {});
        } catch (IOException e) { throw new IllegalStateException("permissions.json is missing or malformed", e); }
        this.matrix = Map.copyOf(doc.permissions());
        this.endpoints = List.copyOf(doc.endpoints());
        var index = new LinkedHashMap<String, Endpoint>();
        for (Endpoint e : endpoints) {
            if (!matrix.containsKey(e.permission())) throw new IllegalStateException("permissions.json: endpoint " + e.method() + " " + e.path() + " uses unknown permission " + e.permission());
            index.put(key(e.method(), e.path()), e);
        }
        this.byRoute = Map.copyOf(index);
    }

    public Map<String, List<String>> matrix() { return matrix; }
    public List<Endpoint> endpoints() { return endpoints; }

    public Optional<Endpoint> find(String method, String path) { return Optional.ofNullable(byRoute.get(key(method, path))); }

    /** The roles a permission is granted to. */
    public List<String> roles(String permission) { return matrix.getOrDefault(permission, List.of()); }

    /** True when `permissions.json` declares the key at all — what `PreAuthorizeCoverageTest` checks the annotations against. */
    public boolean isDeclared(String permission) { return matrix.containsKey(permission); }

    /** What `@permit.has('lesson.publish')` resolves to: is this permission granted to this role? */
    public boolean grants(String role, String permission) { return role != null && roles(permission).contains(role); }

    /** Every permission key a role holds — the payload of `GET /me/permissions`. */
    public List<String> forRole(String role) { return matrix.entrySet().stream().filter(e -> e.getValue().contains(role)).map(Map.Entry::getKey).sorted().toList(); }

    private static String key(String method, String path) { return method.toUpperCase(Locale.ROOT) + " " + path; }
}
