package quest.server.chat;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What crosses the {@link ChatBus}: enough to build the socket frame on any instance without a lookup. `kind` is
 * `message`, `read` or `typing`; `senderKey` is the session key (`parent:<parentId>` / `teacher:<userId>`) the
 * command came from, so its own sessions get the {@code clientId} echo and nobody else does.
 *
 * <p>{@code messageJson} is the encoded `ChatMessage`, or null when it would push the PostgreSQL `NOTIFY` payload
 * past its 8 000-byte limit — then {@link ChatHub} loads the row by {@code messageId} instead.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatEvent(String kind, String schoolId, String threadId, String childId, String teacherId, String parentId,
                        String senderKey, String clientId, String messageId, String messageJson, String sender, Long at) {
    public static final String MESSAGE = "message", READ = "read", TYPING = "typing";

    public static ChatEvent message(String schoolId, String threadId, String childId, String teacherId, String parentId,
                                    String senderKey, String clientId, String messageId, String messageJson) {
        return new ChatEvent(MESSAGE, schoolId, threadId, childId, teacherId, parentId, senderKey, clientId, messageId, messageJson, null, null);
    }
    public static ChatEvent read(String schoolId, String threadId, String childId, String teacherId, String parentId, String senderKey, String readBy, long at) {
        return new ChatEvent(READ, schoolId, threadId, childId, teacherId, parentId, senderKey, null, null, null, readBy, at);
    }
    public static ChatEvent typing(String schoolId, String threadId, String childId, String teacherId, String parentId, String senderKey, String from) {
        return new ChatEvent(TYPING, schoolId, threadId, childId, teacherId, parentId, senderKey, null, null, null, from, null);
    }
    public ChatEvent withoutMessageJson() { return new ChatEvent(kind, schoolId, threadId, childId, teacherId, parentId, senderKey, clientId, messageId, null, sender, at); }
}
