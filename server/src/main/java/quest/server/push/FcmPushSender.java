package quest.server.push;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.ApsAlert;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quest.api.dto.DevicePlatform;
import quest.api.dto.PushMessage;

/**
 * Firebase Cloud Messaging through the Admin SDK, on the Firebase app {@code FirebaseTokenFilter} initialises — on
 * Cloud Run that is Application Default Credentials, the runtime service account, which Terraform grants
 * `roles/firebasecloudmessaging.admin`.
 *
 * <p><strong>Android: data-only, high priority.</strong> The app's `FirebaseMessagingService` then runs in the
 * foreground, the background and after a swipe-away alike and draws the notification itself — in its own channel, in
 * the phone's current language from `kind`, with a tap that opens `link`. A notification message would be drawn by the
 * system tray with none of that while the app is in the background. <strong>iOS: an APNs alert beside the same
 * data</strong>, because iOS does not wake a closed app for a data-only push; it works as soon as an APNs key is
 * uploaded to the Firebase project, with no code change.
 */
class FcmPushSender implements PushSender {
    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);
    /** A push nobody received in two days is stale news: the app shows it from `/me/notifications` anyway. */
    static final Duration TTL = Duration.ofDays(2);

    @Override public Outcome send(String token, DevicePlatform platform, PushMessage message) {
        if (FirebaseApp.getApps().isEmpty()) {
            log.warn("push: Firebase is not initialised (FAKE_AUTH or no credentials); nothing sent");
            return Outcome.FAILED;
        }
        try {
            FirebaseMessaging.getInstance().send(message(token, platform, message));
            return Outcome.SENT;
        } catch (FirebaseMessagingException e) {
            var code = e.getMessagingErrorCode();
            log.debug("push: FCM answered {} ({})", code, e.getErrorCode());
            return outcome(code);
        } catch (RuntimeException e) {
            log.warn("push: FCM send failed: {}", e.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    static Outcome outcome(MessagingErrorCode code) {
        if (code == null) return Outcome.RETRY;
        return switch (code) {
            case UNREGISTERED, INVALID_ARGUMENT, SENDER_ID_MISMATCH -> Outcome.DEAD_TOKEN;
            case UNAVAILABLE, INTERNAL, QUOTA_EXCEEDED -> Outcome.RETRY;
            case THIRD_PARTY_AUTH_ERROR -> Outcome.FAILED;
        };
    }

    static Message message(String token, DevicePlatform platform, PushMessage push) {
        var b = Message.builder().setToken(token).putAllData(push.toData());
        return switch (platform) {
            case ANDROID -> b.setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH)
                    .setCollapseKey(push.getCollapseKey()).setTtl(TTL.toMillis()).build()).build();
            case IOS -> b.setApnsConfig(ApnsConfig.builder()
                    .putHeader("apns-collapse-id", apnsCollapseId(push.getCollapseKey()))
                    .putHeader("apns-priority", "10")
                    .setAps(Aps.builder().setAlert(ApsAlert.builder().setTitle(push.getTitle()).setBody(push.getBody()).build())
                            .setSound("default").setMutableContent(true).setThreadId(push.getCollapseKey()).build())
                    .build()).build();
        };
    }

    /** APNs takes at most 64 bytes here; a longer key is replaced by a stable digest of itself. */
    static String apnsCollapseId(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length <= 64) return key;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))).substring(0, 64);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
