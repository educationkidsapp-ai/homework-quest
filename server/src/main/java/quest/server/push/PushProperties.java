package quest.server.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * `quest.push.*` (B4). `enabled` (QUEST_PUSH_ENABLED) sends through Firebase Cloud Messaging — on in `qa` and `prod`,
 * off everywhere else, where {@link RecordingPushSender} keeps what would have gone out. `concurrency` is how many sends
 * run at once (8, at most 16) and `queue-capacity` how many may wait (2000; beyond that a fan-out's overflow is dropped
 * and logged). `max-attempts` and `backoff-millis` bound the retries of a send FCM answered with a transient error
 * (3 attempts, 1 s then 4 s, each ±50 %).
 */
@ConfigurationProperties(prefix = "quest.push")
public record PushProperties(boolean enabled, Integer maxAttempts, Long backoffMillis, Integer concurrency, Integer queueCapacity) {
    public int attempts() { return maxAttempts == null || maxAttempts < 1 ? 3 : Math.min(maxAttempts, 5); }
    public long backoff() { return backoffMillis == null || backoffMillis < 0 ? 1000 : backoffMillis; }
    public int threads() { return concurrency == null || concurrency < 1 ? 8 : Math.min(concurrency, 16); }
    public int queue() { return queueCapacity == null || queueCapacity < 1 ? 2000 : queueCapacity; }
}
