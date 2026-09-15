package quest.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;

/**
 * Refresh tokens: 30 days, stored as a SHA-256 hash (the value itself only ever exists in the response body), rotated
 * on every use. Presenting a token that was already rotated away or signed out is treated as theft: every live token
 * of that user — the whole family — is revoked and the caller has to sign in again.
 */
@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository tokens; private final Duration ttl;
    public RefreshTokenService(RefreshTokenRepository tokens, QuestProperties props) {
        this.tokens = tokens;
        long days = props.auth().refreshDays() <= 0 ? 30 : props.auth().refreshDays();
        this.ttl = Duration.ofDays(days);
    }

    /** The clear-text token; only its hash is kept. */
    public String issue(String userId) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var row = new Entities.RefreshTokenEntity();
        row.setId(UUID.randomUUID().toString()); row.setUserId(userId); row.setTokenHash(hash(token));
        row.setExpiresAt(Instant.now().plus(ttl)); row.setCreatedAt(Instant.now());
        tokens.save(row);
        return token;
    }

    /** Rotation: the presented token is revoked and a fresh one returned. Returns the user the pair belongs to. */
    public Rotated rotate(String presented) {
        var row = tokens.findByTokenHash(hash(presented)).orElseThrow(() -> ApiException.unauthorized("That session has expired. Sign in again."));
        if (row.getRevokedAt() != null) {
            revokeAll(row.getUserId());
            log.warn("refresh token reuse for user {}; the whole family was revoked", row.getUserId());
            throw ApiException.unauthorized("That session has expired. Sign in again.");
        }
        if (row.getExpiresAt().isBefore(Instant.now())) { revoke(row); throw ApiException.unauthorized("That session has expired. Sign in again."); }
        revoke(row);
        return new Rotated(row.getUserId(), issue(row.getUserId()));
    }

    public record Rotated(String userId, String refreshToken) {}

    /** Sign-out: a token that is unknown or already revoked is not an error (the session is gone either way). */
    public void revoke(String presented) { tokens.findByTokenHash(hash(presented)).filter(r -> r.getRevokedAt() == null).ifPresent(this::revoke); }

    public void revokeAll(String userId) { tokens.findByUserIdAndRevokedAtIsNull(userId).forEach(this::revoke); }

    private void revoke(Entities.RefreshTokenEntity row) { row.setRevokedAt(Instant.now()); tokens.save(row); }

    static String hash(String token) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
