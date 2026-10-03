package quest.server.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.Test;
import quest.api.dto.DevicePlatform;
import quest.api.dto.NotificationKind;
import quest.api.dto.PushMessage;
import quest.server.push.PushSender.Outcome;

/** B4: what FCM's answers mean to the sender, and the two message shapes it builds. No network. */
class FcmPushSenderTest {
    private static final PushMessage PUSH = new PushMessage(NotificationKind.EXAM_RELEASED, "Results ready: Maths", "Maya's result is ready.",
            "n-1", "c-1", "/children/c-1/progress", null, "lesson:" + "x".repeat(80));

    @Test void a_dead_token_is_pruned_a_transient_error_retried_and_the_rest_dropped() {
        for (var code : new MessagingErrorCode[] {MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH})
            assertThat(FcmPushSender.outcome(code)).isEqualTo(Outcome.DEAD_TOKEN);
        for (var code : new MessagingErrorCode[] {MessagingErrorCode.UNAVAILABLE, MessagingErrorCode.INTERNAL, MessagingErrorCode.QUOTA_EXCEEDED})
            assertThat(FcmPushSender.outcome(code)).isEqualTo(Outcome.RETRY);
        assertThat(FcmPushSender.outcome(MessagingErrorCode.THIRD_PARTY_AUTH_ERROR)).as("APNs not set up yet").isEqualTo(Outcome.FAILED);
    }

    @Test void an_apns_collapse_id_fits_in_64_bytes_and_is_stable() {
        assertThat(FcmPushSender.apnsCollapseId("chat:t-1")).isEqualTo("chat:t-1");
        String long1 = FcmPushSender.apnsCollapseId(PUSH.getCollapseKey());
        assertThat(long1).hasSize(64).isEqualTo(FcmPushSender.apnsCollapseId(PUSH.getCollapseKey()));
    }

    @Test void both_platforms_build_a_message() {
        assertThat(FcmPushSender.message("tok", DevicePlatform.ANDROID, PUSH)).isNotNull();
        assertThat(FcmPushSender.message("tok", DevicePlatform.IOS, PUSH)).isNotNull();
    }
}
