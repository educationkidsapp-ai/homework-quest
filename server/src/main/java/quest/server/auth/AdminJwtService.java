package quest.server.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;
import quest.server.config.QuestProperties;

/**
 * HS256 dashboard sessions. Tokens are prefixed `admin.` so the two filters never confuse each other; a password-reset
 * token is prefixed `reset.` and is one-time by construction (it pins the password hash it was issued against, so
 * using it invalidates it). The access token lives 15 minutes and is paired with a refresh token (see
 * {@link RefreshTokenService}); the `/admin/auth/sign-in` alias keeps issuing the long `jwt-hours` session `webAdmin/` expects.
 */
@Service
public class AdminJwtService {
    public static final Set<String> ROLES = Set.of("ADMIN", "TEACHER", "MANAGERIAL");
    private static final String PREFIX = "admin.";
    private static final String RESET_PREFIX = "reset.";

    private final SecretKey key;
    private final Duration legacyTtl, accessTtl, impersonateTtl, resetTtl;

    public AdminJwtService(QuestProperties props) {
        String secret = props.auth().jwtSecret();
        if (secret == null || secret.length() < 32) secret = (secret == null ? "" : secret) + "-homework-quest-dev-secret-please-change-me";
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.legacyTtl = Duration.ofHours(positive(props.auth().jwtHours(), 12));
        this.accessTtl = Duration.ofMinutes(positive(props.auth().accessMinutes(), 15));
        this.impersonateTtl = Duration.ofMinutes(positive(props.auth().impersonateMinutes(), 30));
        this.resetTtl = Duration.ofMinutes(positive(props.auth().resetMinutes(), 60));
    }

    public record Issued(String token, Instant expiresAt) {}

    /** The long session `webAdmin/` still relies on (`POST /admin/auth/sign-in`). `schoolId` is null for ADMIN. */
    public Issued issue(String userId, String email, String role, String schoolId) { return sign(userId, email, role, schoolId, null, legacyTtl); }

    /** The dashboard's 15-minute access token (`POST /auth/sign-in`, `POST /auth/refresh`). */
    public Issued issueAccess(String userId, String email, String role, String schoolId) { return sign(userId, email, role, schoolId, null, accessTtl); }

    /** §5 "View as…": the target's role and school, `impersonating=true` and the Admin who asked in `actor`. */
    public Issued issueImpersonation(String userId, String email, String role, String schoolId, String actorUserId) {
        return sign(userId, email, role, schoolId, actorUserId, impersonateTtl);
    }

    private Issued sign(String userId, String email, String role, String schoolId, String actorUserId, Duration ttl) {
        Instant exp = Instant.now().plus(ttl);
        var builder = Jwts.builder().subject(userId).claim("email", email).claim("role", role).issuedAt(new Date()).expiration(Date.from(exp));
        if (schoolId != null) builder.claim("schoolId", schoolId);
        if (actorUserId != null) builder.claim("impersonating", true).claim("actor", actorUserId);
        return new Issued(PREFIX + builder.signWith(key).compact(), exp);
    }

    public Optional<Principals.User> verify(String token) {
        if (token == null || !token.startsWith(PREFIX)) return Optional.empty();
        try {
            Claims c = claims(token.substring(PREFIX.length()));
            String role = c.get("role", String.class);
            if (role == null) return Optional.empty();
            role = "admin".equals(role) ? "ADMIN" : role.toUpperCase(Locale.ROOT);   // tokens issued before the roles migration
            if (!ROLES.contains(role)) return Optional.empty();
            String actor = Boolean.TRUE.equals(c.get("impersonating", Boolean.class)) ? c.get("actor", String.class) : null;
            return Optional.of(new Principals.User(c.getSubject(), c.get("email", String.class), role, c.get("schoolId", String.class), actor));
        } catch (Exception e) { return Optional.empty(); }
    }

    // ---- password reset: no row to store, because the token pins the hash it was issued against

    /** One-time by construction: `pv` is a digest of the current password hash, so a used link stops verifying. */
    public String issueReset(String userId, String passwordHash) {
        Instant exp = Instant.now().plus(resetTtl);
        return RESET_PREFIX + Jwts.builder().subject(userId).claim("pv", digest(passwordHash)).claim("typ", "reset")
                .issuedAt(new Date()).expiration(Date.from(exp)).signWith(key).compact();
    }

    /** The user id the link was issued for, when the link is still valid against that user's current password hash. */
    public Optional<String> verifyReset(String token, java.util.function.Function<String, String> currentPasswordHash) {
        if (token == null || !token.startsWith(RESET_PREFIX)) return Optional.empty();
        try {
            Claims c = claims(token.substring(RESET_PREFIX.length()));
            if (!"reset".equals(c.get("typ", String.class))) return Optional.empty();
            String userId = c.getSubject();
            String hash = currentPasswordHash.apply(userId);
            if (hash == null || !digest(hash).equals(c.get("pv", String.class))) return Optional.empty();
            return Optional.of(userId);
        } catch (Exception e) { return Optional.empty(); }
    }

    private Claims claims(String jwt) { return Jwts.parser().verifyWith(key).build().parseSignedClaims(jwt).getPayload(); }

    private static String digest(String value) {
        try {
            var sha = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sha).substring(0, 16);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static long positive(long value, long fallback) { return value <= 0 ? fallback : value; }
}
