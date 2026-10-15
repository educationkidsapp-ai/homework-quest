package quest.server.translate;

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
 * Feature 29: 60 translation requests an hour per person, as a sliding window in memory — per instance, like
 * {@link quest.server.chat.ChatRateLimiter} and {@link quest.server.auth.SignInRateLimiter}: with two Cloud Run
 * instances the real ceiling is twice that, which still stops a runaway client without a shared store.
 *
 * <p>Sixty is one reader working through an inbox, not a client translating the school. <strong>Every request is
 * counted, a cache hit included</strong>, and that is deliberate: the cache stops the bill but not the traffic, and
 * the thing worth refusing is a loop. A reader who asks for the same message twice is inside sixty either way.
 */
@Component
public class TranslationRateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);
    private final int perHour; private final Clock clock;
    private final Map<String, Deque<Instant>> asked = new ConcurrentHashMap<>();

    public TranslationRateLimiter(Clock clock, @Value("${quest.translate.per-hour:60}") int perHour) {
        this.clock = clock; this.perHour = perHour <= 0 ? 60 : perHour;
    }

    /** Records one request for the key, or throws 429 `rate_limited` when the last hour already holds the limit. */
    public void record(String readerKey) {
        Instant now = clock.instant();
        var window = asked.computeIfAbsent(readerKey, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(WINDOW))) window.pollFirst();
            if (window.size() >= perHour) throw ApiException.rateLimited("That is more than " + perHour + " translations in an hour — try again later.");
            window.addLast(now);
        }
        if (asked.size() > 10_000) asked.values().removeIf(d -> { synchronized (d) { return d.isEmpty() || d.peekLast().isBefore(now.minus(WINDOW)); } });
    }
}
