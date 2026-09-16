package quest.server.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;
import quest.server.config.QuestProperties;

/**
 * 10 failed sign-ins per email + IP per 15 minutes (configurable), and a second, wider bucket on the email alone so a
 * spray from many addresses still runs out of attempts. Both buckets key on the normalised (trimmed, lowercased)
 * email, and the IP half is {@link AuthController#clientIp} — the peer, never a header the caller wrote. In memory on
 * purpose: one Cloud Run instance is enough to slow a password-spray down, and a shared store is not worth a
 * dependency at this stage.
 */
@Component
public class SignInRateLimiter {
    /** How much more a single email may fail from every address put together before it is throttled too. */
    private static final int EMAIL_BUCKET_FACTOR = 5;

    private final int attempts; private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public SignInRateLimiter(QuestProperties props) {
        this.attempts = props.auth().signInAttempts() <= 0 ? 10 : props.auth().signInAttempts();
        this.window = Duration.ofMinutes(props.auth().signInWindowMinutes() <= 0 ? 15 : props.auth().signInWindowMinutes());
    }

    /** Throws 429 when this email + IP, or this email anywhere, has already used up its attempts. */
    public void check(String email, String ip) {
        checkBucket(key(email, ip), attempts);
        checkBucket(key(email, null), attempts * EMAIL_BUCKET_FACTOR);
    }

    public void recordFailure(String email, String ip) {
        record(key(email, ip));
        record(key(email, null));
        prune();
    }

    /**
     * Drops buckets whose every entry has aged out, once the map is large enough for that to be worth doing. Called
     * from every path that adds a key — {@link #recordFailure} and {@link #probe} — because a public route that
     * only ever added would grow the map without bound even while the rate limit itself held.
     */
    private void prune() {
        if (failures.size() > 10_000) failures.values().removeIf(d -> { synchronized (d) { prune(d); return d.isEmpty(); } });
    }

    public void recordSuccess(String email, String ip) { failures.remove(key(email, ip)); failures.remove(key(email, null)); }

    /**
     * A public lookup that must not become a way to sweep the user table — `POST /schools/logo` (§6 screen 1) —
     * throttled with the same window as sign-in but in a bucket of its own, named by {@code label}.
     *
     * <p>Two differences from {@link #check}. <em>Every</em> call counts, not only the failures: the point is to stop
     * a caller trying a thousand addresses, and most of those would "succeed" in returning 204. And the wide bucket
     * is keyed on the address alone being absent — one per IP rather than per email — because a sweep is exactly the
     * case where the email changes every time and the IP does not.
     *
     * <p>Its own bucket also means throttling a logo lookup can never use up a real sign-in's ten attempts.
     */
    public void probe(String label, String value, String ip) {
        String narrow = label + "|" + key(value, ip);
        String wide = label + "|*|" + (ip == null ? "*" : ip);
        checkBucket(narrow, attempts);
        checkBucket(wide, attempts * EMAIL_BUCKET_FACTOR);
        record(narrow);
        record(wide);
        prune();
    }

    private void checkBucket(String key, int allowed) {
        var recent = failures.get(key);
        if (recent == null) return;
        synchronized (recent) {
            prune(recent);
            if (recent.size() >= allowed) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many attempts. Try again in a few minutes.");
        }
    }

    private void record(String key) {
        var recent = failures.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (recent) { prune(recent); recent.addLast(Instant.now()); }
    }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = Instant.now().minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.removeFirst();
    }

    /** `ip == null` is the email-only bucket; the email is normalised the same way {@link AuthService#normalise} does. */
    private static String key(String email, String ip) { return (email == null ? "" : email.trim().toLowerCase(Locale.ROOT)) + "|" + (ip == null ? "*" : ip); }
}
