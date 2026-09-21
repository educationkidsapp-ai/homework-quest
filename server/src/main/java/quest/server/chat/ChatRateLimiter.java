package quest.server.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * 30 messages a minute per sender, REST and socket together, as a sliding window in memory — per instance, like
 * {@link quest.server.auth.SignInRateLimiter}: with two Cloud Run instances the real ceiling is twice that, which
 * still stops a runaway client without a shared store.
 */
@Component
public class ChatRateLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private final int perMinute; private final Clock clock;
    private final Map<String, Deque<Instant>> sent = new ConcurrentHashMap<>();

    public ChatRateLimiter(Clock clock, @Value("${quest.chat.messages-per-minute:30}") int perMinute) { this.clock = clock; this.perMinute = perMinute <= 0 ? 30 : perMinute; }

    /** Records one send for the key, or throws 429 `rate_limited` when the last minute already holds the limit. */
    public void record(String senderKey) {
        Instant now = clock.instant();
        var window = sent.computeIfAbsent(senderKey, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) window.pollFirst();
            if (window.size() >= perMinute) throw ApiException.rateLimited("That is more than " + perMinute + " messages in a minute — wait a moment.");
            window.addLast(now);
        }
        if (sent.size() > 10_000) sent.values().removeIf(d -> { synchronized (d) { return d.isEmpty() || d.peekLast().isBefore(now.minus(WINDOW)); } });
    }
}
