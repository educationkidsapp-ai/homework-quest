package quest.server.push;

import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * One push to one phone. {@link FcmPushSender} in `qa` and `prod`, {@link RecordingPushSender} wherever
 * `quest.push.enabled` is off (H2, tests, a laptop). Never throws: what happened is the {@link Outcome}.
 */
public interface PushSender {
    enum Outcome {
        /** FCM accepted it. */
        SENT,
        /** The token is dead — uninstalled, signed out elsewhere, another project's — and is deleted. */
        DEAD_TOKEN,
        /** A transient failure (FCM unavailable, internal, over quota): worth another try. */
        RETRY,
        /** Anything else (not configured, APNs not set up): logged, not retried. */
        FAILED
    }

    Outcome send(String token, DevicePlatform platform, PushMessage message);
}
