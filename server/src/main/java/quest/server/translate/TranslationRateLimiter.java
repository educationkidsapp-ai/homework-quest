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
import quest.server.ai.AiCallRepository;
import quest.server.config.ApiException;

/**
 * Feature 29: 60 translations an hour per person, counted twice over — because one of the two counts does not hold.
 *
 * <ul>
 *   <li><strong>In memory</strong>, as a sliding window, like {@link quest.server.chat.ChatRateLimiter} and
 *       {@link quest.server.auth.SignInRateLimiter}. It counts <em>every</em> request, a cache hit included, which is
 *       deliberate: the cache stops the bill but not the traffic, and what is worth refusing is a loop. But it is
 *       per instance — with two Cloud Run instances the real ceiling is twice sixty, and with ten it is ten times.</li>
 *   <li><strong>Against the `ai_calls` ledger</strong> ({@link AiCallRepository#countSince}), per reader and hour,
 *       which every instance writes to and every instance can read: the <em>model calls</em> a reader can cause in an
 *       hour are sixty however many instances are serving her. That is the count that bounds the bill, and it is the
 *       one the in-memory window cannot be trusted for.</li>
 * </ul>
 *
 * <p>Sixty is one reader working through an inbox, not a client translating the school.
 */
@Component
public class TranslationRateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);
    private final int perHour; private final Clock clock; private final AiCallRepository ledger;
    private final Map<String, Deque<Instant>> asked = new ConcurrentHashMap<>();

    public TranslationRateLimiter(Clock clock, AiCallRepository ledger, @Value("${quest.translate.per-hour:60}") int perHour) {
        this.clock = clock; this.ledger = ledger; this.perHour = perHour <= 0 ? 60 : perHour;
    }

    /**
     * The durable half, asked only when the cache has missed and a model call is about to be made: 429 when this
     * reader has already caused the hour's worth of calls on <em>any</em> instance. The tag is hers
     * ({@link #tag}), so another reader's calls are not counted against her and another school's cannot be.
     */
    public void requireHeadroom(String schoolId, String feature, String readerKey) {
        long already = ledger.countSince(schoolId, feature, tag(readerKey), clock.instant().minus(WINDOW));
        if (already >= perHour) throw ApiException.rateLimited("That is more than " + perHour + " translations in an hour — try again later.");
    }

    /** `ai_calls.tag` for one reader: what {@link #requireHeadroom} counts, written by the call it bounds. */
    public static String tag(String readerKey) { return "TRANSLATE-" + readerKey; }

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
