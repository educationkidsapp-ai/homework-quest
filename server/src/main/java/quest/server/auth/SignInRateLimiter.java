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
 * 10 failed sign-ins per email + IP per 15 minutes (configurable). In memory on purpose: one Cloud Run instance is
 * enough to slow a password-spray down, and a shared store is not worth a dependency at this stage.
 */
@Component
public class SignInRateLimiter {
    private final int attempts; private final Duration window;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public SignInRateLimiter(QuestProperties props) {
        this.attempts = props.auth().signInAttempts() <= 0 ? 10 : props.auth().signInAttempts();
        this.window = Duration.ofMinutes(props.auth().signInWindowMinutes() <= 0 ? 15 : props.auth().signInWindowMinutes());
    }

    /** Throws 429 when this email + IP has already used up its attempts. */
    public void check(String email, String ip) {
        var recent = failures.get(key(email, ip));
        if (recent == null) return;
        synchronized (recent) {
            prune(recent);
            if (recent.size() >= attempts) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many attempts. Try again in a few minutes.");
        }
    }

    public void recordFailure(String email, String ip) {
        var recent = failures.computeIfAbsent(key(email, ip), k -> new ArrayDeque<>());
        synchronized (recent) { prune(recent); recent.addLast(Instant.now()); }
        if (failures.size() > 10_000) failures.values().removeIf(d -> { synchronized (d) { prune(d); return d.isEmpty(); } });
    }

    public void recordSuccess(String email, String ip) { failures.remove(key(email, ip)); }

    private void prune(Deque<Instant> recent) {
        Instant cutoff = Instant.now().minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) recent.removeFirst();
    }

    private static String key(String email, String ip) { return (email == null ? "" : email.trim().toLowerCase(Locale.ROOT)) + "|" + (ip == null ? "" : ip); }
}
