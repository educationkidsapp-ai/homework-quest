package quest.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import quest.server.config.QuestProperties;

/** HS256 admin sessions. Tokens are prefixed `admin.` so the two filters never confuse each other. */
@Service
public class AdminJwtService {
    private final SecretKey key;
    private final Duration ttl;

    public AdminJwtService(QuestProperties props) {
        String secret = props.auth().jwtSecret();
        if (secret == null || secret.length() < 32) secret = (secret == null ? "" : secret) + "-homework-quest-dev-secret-please-change-me";
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(props.auth().jwtHours() <= 0 ? 12 : props.auth().jwtHours());
    }

    public record Issued(String token, Instant expiresAt) {}

    public Issued issue(String adminId, String email) {
        Instant exp = Instant.now().plus(ttl);
        String jwt = Jwts.builder().subject(adminId).claim("email", email).claim("role", "admin").issuedAt(new Date()).expiration(Date.from(exp)).signWith(key).compact();
        return new Issued("admin." + jwt, exp);
    }

    public Optional<Principals.Admin> verify(String token) {
        if (token == null || !token.startsWith("admin.")) return Optional.empty();
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token.substring(6)).getPayload();
            if (!"admin".equals(c.get("role"))) return Optional.empty();
            return Optional.of(new Principals.Admin(c.getSubject(), c.get("email", String.class)));
        } catch (Exception e) { return Optional.empty(); }
    }
}
