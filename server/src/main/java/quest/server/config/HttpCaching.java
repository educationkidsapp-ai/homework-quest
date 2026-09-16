package quest.server.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * The public, cacheable GETs (§3: the theme is "public, cached, ETag"). The body is hashed into a strong ETag, so a
 * client that already has the current theme or flag set gets a 304 with no payload, and every answer carries
 * `Cache-Control: public, max-age=…` for the CDN and the app's HTTP cache.
 */
public final class HttpCaching {
    /** §3/§4: five minutes. A flag flip or a theme save is visible to a client within that, without a rebuild. */
    public static final long PUBLIC_MAX_AGE_SECONDS = 300;

    private HttpCaching() {}

    /** 200 with an ETag, or 304 when `If-None-Match` already names this body. */
    public static <T> ResponseEntity<T> cached(T body, String serialisedBody, String ifNoneMatch) {
        String tag = etag(serialisedBody);
        var control = CacheControl.maxAge(Duration.ofSeconds(PUBLIC_MAX_AGE_SECONDS)).cachePublic();
        if (matches(ifNoneMatch, tag)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(tag).cacheControl(control).build();
        return ResponseEntity.ok().eTag(tag).cacheControl(control).body(body);
    }

    /** A strong ETag: the quoted SHA-256 of the body, so it changes exactly when the answer does. */
    public static String etag(String body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest).substring(0, 32) + "\"";
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * `If-None-Match` is a comma-separated list and each entry may be weak (`W/"…"`); `*` matches anything the
     * client might hold.
     */
    private static boolean matches(String ifNoneMatch, String tag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.startsWith("W/")) trimmed = trimmed.substring(2);
            if ("*".equals(trimmed) || trimmed.equals(tag)) return true;
        }
        return false;
    }
}
