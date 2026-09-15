package quest.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import quest.server.config.QuestProperties;

/** HS256 dashboard sessions. Tokens are prefixed `admin.` so the two filters never confuse each other. */
@Service
public class AdminJwtService {
    public static final Set<String> ROLES = Set.of("ADMIN", "TEACHER", "MANAGERIAL");
    private final SecretKey key;
    private final Duration ttl;

    public AdminJwtService(QuestProperties props) {
        String secret = props.auth().jwtSecret();
        if (secret == null || secret.length() < 32) secret = (secret == null ? "" : secret) + "-homework-quest-dev-secret-please-change-me";
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(props.auth().jwtHours() <= 0 ? 12 : props.auth().jwtHours());
    }

    public record Issued(String token, Instant expiresAt) {}

    /** `role` is ADMIN | TEACHER | MANAGERIAL; `schoolId` is null for ADMIN (the platform owner). */
    public Issued issue(String userId, String email, String role, String schoolId) {
        Instant exp = Instant.now().plus(ttl);
        var builder = Jwts.builder().subject(userId).claim("email", email).claim("role", role).issuedAt(new Date()).expiration(Date.from(exp));
        if (schoolId != null) builder.claim("schoolId", schoolId);
        return new Issued("admin." + builder.signWith(key).compact(), exp);
    }

    public Optional<Principals.User> verify(String token) {
        if (token == null || !token.startsWith("admin.")) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token.substring(6)).getPayload();
            String role = c.get("role", String.class);
            if (role == null) return Optional.empty();
            role = "admin".equals(role) ? "ADMIN" : role.toUpperCase();   // tokens issued before the roles migration
            if (!ROLES.contains(role)) return Optional.empty();
            return Optional.of(new Principals.User(c.getSubject(), c.get("email", String.class), role, c.get("schoolId", String.class)));
        } catch (Exception e) { return Optional.empty(); }
    }
}
