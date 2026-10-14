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
            "n-1", "c-1", "/children/c-1/progress", null, "lesson:" + "x".repeat(80), null, null, null, null, false);
    /** Feature 19: the same message, quietly — a parent's line that reached a teacher outside her office hours. */
    private static final PushMessage QUIET = new PushMessage(NotificationKind.CHAT_MESSAGE, "Message from Hala", "Is Maya all right?",
            "n-2", null, "/teacher/chat?thread=t-1", null, "chat:t-1", null, null, null, "t-1", true);

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

    /**
     * Feature 19: a quiet push is data-only on Android like every other, at normal FCM priority rather than high, and
     * on iOS at `apns-priority` 5 with no sound. A loud one is what it always was.
     */
    @Test void a_quiet_push_drops_the_priority_and_the_sound() throws Exception {
        var android = FcmPushSender.message("tok", DevicePlatform.ANDROID, QUIET);
        assertThat(field(field(android, "androidConfig"), "priority").toString()).isEqualToIgnoringCase("normal");
        assertThat(data(android)).containsEntry("quiet", "true").containsEntry("threadId", "t-1");

        var ios = FcmPushSender.message("tok", DevicePlatform.IOS, QUIET);
        assertThat(headers(ios)).containsEntry("apns-priority", "5");
        assertThat(apsFields(ios)).as("no sound after hours").doesNotContainKey("sound");

        var loud = FcmPushSender.message("tok", DevicePlatform.ANDROID, PUSH);
        assertThat(field(field(loud, "androidConfig"), "priority").toString()).isEqualToIgnoringCase("high");
        assertThat(data(loud)).doesNotContainKey("quiet");
        assertThat(headers(FcmPushSender.message("tok", DevicePlatform.IOS, PUSH))).containsEntry("apns-priority", "10");
        assertThat(apsFields(FcmPushSender.message("tok", DevicePlatform.IOS, PUSH))).containsEntry("sound", "default");
    }

    // The Firebase SDK keeps a built message's parts private and reads them only on the wire, so the assertions above
    // read the fields themselves rather than send anything.
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> data(com.google.firebase.messaging.Message m) throws Exception {
        return (java.util.Map<String, String>) field(m, "data");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, String> headers(com.google.firebase.messaging.Message m) throws Exception {
        return (java.util.Map<String, String>) field(field(m, "apnsConfig"), "headers");
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> apsFields(com.google.firebase.messaging.Message m) throws Exception {
        var payload = (java.util.Map<String, Object>) field(field(m, "apnsConfig"), "payload");
        var aps = payload.get("aps");
        return (java.util.Map<String, Object>) (aps instanceof java.util.Map<?, ?> map ? map : field(aps, "fields"));
    }

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true);
        return f.get(target);
    }
}
