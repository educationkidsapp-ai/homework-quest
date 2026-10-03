package quest.server.push;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * B4's test helper: register a phone for a parent, and read what the {@link RecordingPushSender} sent it. A push leaves
 * after the commit on a task thread, so a read waits for the expected count and then a little longer, which is what lets
 * "exactly one" mean exactly one rather than "one so far".
 */
public final class PushProbe {
    private static final long WAIT_MILLIS = 5_000, SETTLE_MILLIS = 300;
    private PushProbe() {}

    public static void register(MockMvc mvc, String bearer, String token, String locale) throws Exception {
        String body = "{\"token\":\"" + token + "\",\"platform\":\"ANDROID\",\"appVersion\":\"1.4.0\""
                + (locale == null ? "" : ",\"locale\":\"" + locale + "\"") + "}";
        mvc.perform(post("/me/devices").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
    }

    /** What this token was sent, once at least {@code count} pushes arrived (or the wait ran out) and things went quiet. */
    public static List<RecordingPushSender.Sent> await(PushSender sender, String token, int count) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MILLIS;
        while (sentTo(sender, token).size() < count && System.currentTimeMillis() < until) Thread.sleep(20);
        Thread.sleep(SETTLE_MILLIS);
        return sentTo(sender, token);
    }

    public static List<RecordingPushSender.Sent> sentTo(PushSender sender, String token) {
        return ((RecordingPushSender) sender).sent().stream().filter(s -> s.token().equals(token)).toList();
    }

    public static void script(PushSender sender, String token, PushSender.Outcome... outcomes) { ((RecordingPushSender) sender).script(token, outcomes); }

    /** A token no other test uses, in FCM's alphabet. */
    public static String token(String label) { return label + ":" + java.util.UUID.randomUUID().toString().replace("-", ""); }
}
