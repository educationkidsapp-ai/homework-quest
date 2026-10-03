package quest.server.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * `quest.push.*` (B4). `enabled` (QUEST_PUSH_ENABLED) sends through Firebase Cloud Messaging — on in `qa` and `prod`,
 * off everywhere else, where {@link RecordingPushSender} keeps what would have gone out. `max-attempts` and
 * `backoff-millis` bound the retries of a send FCM answered with a transient error (1 s, then 4 s, by default).
 */
@ConfigurationProperties(prefix = "quest.push")
public record PushProperties(boolean enabled, Integer maxAttempts, Long backoffMillis) {
    public int attempts() { return maxAttempts == null || maxAttempts < 1 ? 3 : Math.min(maxAttempts, 5); }
    public long backoff() { return backoffMillis == null || backoffMillis < 0 ? 1000 : backoffMillis; }
}
