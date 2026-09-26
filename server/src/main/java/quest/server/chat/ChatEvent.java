package quest.server.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What crosses the {@link ChatBus}: enough to build the socket frame on any instance without a lookup. `kind` is
 * `message`, `read`, `typing`, `status` (R4) or — since E2 (D26) — `notification`; `senderKey` is the session key
 * (`parent:<parentId>` / `user:<userId>`) the command came from, so its own sessions get the {@code clientId} echo
 * and nobody else does.
 *
 * <p>A `notification` is the one event that names a person rather than a thread: {@code userId} is the dashboard
 * user it belongs to and {@code notificationJson} is the finished `ChatFrame.Notification`, ready to write to her
 * sockets. It is small by construction (the title and body are clipped where the row is written), so it never
 * meets the NOTIFY payload limit the message path has to.
 *
 * <p><strong>Rolling deploys.</strong> `ignoreUnknown` is on the type, not left to the global Jackson setting: during
 * a Cloud Run rollout the two revisions share one `chat_events` channel, so an old instance is handed components it
 * has never heard of. It drops the fields and delivers the frame rather than logging "unreadable notification" and
 * losing the message — which is what every future component added here depends on.
 *
 * <p>R4 added {@code peerUserId}: the second staff member of a coordinator-to-manager thread, so {@link ChatHub}
 * reaches both of them without a lookup, and null on every thread that has a parent on it instead.
 *
 * <p>{@code messageJson} is the encoded `ChatMessage`, or null when it would push the PostgreSQL `NOTIFY` payload
 * past its 8 000-byte limit — then {@link ChatHub} loads the row by {@code messageId} instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatEvent(String kind, String schoolId, String threadId, String childId, String teacherId, String parentId,
                        String senderKey, String clientId, String messageId, String messageJson, String sender, Long at,
                        String userId, String notificationJson, String peerUserId, String status) {
    public static final String MESSAGE = "message", READ = "read", TYPING = "typing", NOTIFICATION = "notification", STATUS = "status";

    public static ChatEvent message(String schoolId, String threadId, String childId, String teacherId, String parentId, String peerUserId,
                                    String senderKey, String clientId, String messageId, String messageJson) {
        return new ChatEvent(MESSAGE, schoolId, threadId, childId, teacherId, parentId, senderKey, clientId, messageId, messageJson, null, null, null, null, peerUserId, null);
    }
    public static ChatEvent read(String schoolId, String threadId, String childId, String teacherId, String parentId, String peerUserId, String senderKey, String readBy, long at) {
        return new ChatEvent(READ, schoolId, threadId, childId, teacherId, parentId, senderKey, null, null, null, readBy, at, null, null, peerUserId, null);
    }
    public static ChatEvent typing(String schoolId, String threadId, String childId, String teacherId, String parentId, String peerUserId, String senderKey, String from) {
        return new ChatEvent(TYPING, schoolId, threadId, childId, teacherId, parentId, senderKey, null, null, null, from, null, null, null, peerUserId, null);
    }
    /** E2: one dashboard user's bell. `frameJson` is the encoded `ChatFrame.Notification`; `schoolId` is the row's. */
    public static ChatEvent notification(String schoolId, String userId, String frameJson) {
        return new ChatEvent(NOTIFICATION, schoolId, null, null, null, null, null, null, null, null, null, null, userId, frameJson, null, null);
    }
    /** R4: the staff side moved a thread between `open` and `resolved`; both parties are told. */
    public static ChatEvent status(String schoolId, String threadId, String childId, String teacherId, String parentId, String peerUserId, String status, long at) {
        return new ChatEvent(STATUS, schoolId, threadId, childId, teacherId, parentId, null, null, null, null, null, at, null, null, peerUserId, status);
    }
    public ChatEvent withoutMessageJson() { return new ChatEvent(kind, schoolId, threadId, childId, teacherId, parentId, senderKey, clientId, messageId, null, sender, at, userId, notificationJson, peerUserId, status); }
}
