package quest.server.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quest.api.dto.ChatFrame;
import quest.api.dto.ChatMessage;
import quest.server.config.Json;

/**
 * The bus's one subscriber on each instance: turns a {@link ChatEvent} into frames for the sockets held here — the
 * parties of a thread — two of them, or, on a staff-to-staff thread, the two staff members — or, for E2's
 * `notification`, the one dashboard user it belongs to. The sender's own
 * sessions get the message too — with the {@code clientId} it was sent with, which is the ack — so a parent's
 * second device and the dashboard tab beside the one that typed all see the same thread.
 */
@Component
public class ChatHub {
    private static final Logger log = LoggerFactory.getLogger(ChatHub.class);
    private final ChatSessions sessions; private final ChatMessageRepository messages; private final Json json;

    public ChatHub(ChatBus bus, ChatSessions sessions, ChatMessageRepository messages, Json json) {
        this.sessions = sessions; this.messages = messages; this.json = json;
        bus.subscribe(this::deliver);
    }

    void deliver(ChatEvent e) {
        String[] keys = keys(e);
        switch (e.kind()) {
            case ChatEvent.MESSAGE -> {
                ChatMessage message = message(e);
                if (message == null) return;
                for (String key : keys) {
                    boolean own = key.equals(e.senderKey());
                    sessions.send(key, encode(new ChatFrame.Message(message, own ? e.clientId() : null)));
                }
            }
            case ChatEvent.READ -> {
                String frame = encode(new ChatFrame.Read(e.threadId(), ChatService.sender(e.sender()), e.at()));
                for (String key : keys) sessions.send(key, frame);
            }
            case ChatEvent.STATUS -> {
                String frame = encode(new ChatFrame.Status(e.threadId(), ChatService.status(e.status()), e.at()));
                for (String key : keys) sessions.send(key, frame);
            }
            // Everyone on the thread except the person typing — her own other devices do not need to be told.
            case ChatEvent.TYPING -> {
                String frame = encode(new ChatFrame.Typing(e.threadId(), ChatService.sender(e.sender())));
                for (String key : keys) if (!key.equals(e.senderKey())) sessions.sendDroppable(key, frame);
            }
            // E2: not a thread at all — one dashboard user's bell, already encoded, and only on her school's sessions.
            case ChatEvent.NOTIFICATION -> sessions.sendToSchool(ChatService.key(ChatService.USER, e.userId()), e.schoolId(), e.notificationJson());
            default -> log.warn("chat: unknown event kind {}", e.kind());
        }
    }

    /**
     * Every session key a thread event reaches: the staff peer, the child's parent when there is one, and — R4 — the
     * second staff member of a coordinator-to-manager thread. A `typing` frame goes to the others rather than to all.
     */
    private static String[] keys(ChatEvent e) {
        var out = new java.util.ArrayList<String>(3);
        if (e.teacherId() != null) out.add(ChatService.key(ChatService.USER, e.teacherId()));
        if (e.parentId() != null) out.add(ChatService.key(ChatService.PARENT, e.parentId()));
        if (e.peerUserId() != null) out.add(ChatService.key(ChatService.USER, e.peerUserId()));
        return out.toArray(String[]::new);
    }

    /** The message as published, or loaded by id when the `NOTIFY` payload could not carry it. */
    private ChatMessage message(ChatEvent e) {
        if (e.messageJson() != null) return json.decodeShared(e.messageJson(), ChatMessage.Companion.serializer());
        return messages.findOneById(e.messageId()).map(ChatService::dto).orElse(null);
    }

    String encode(ChatFrame frame) { return json.encodeShared(frame, ChatFrame.Companion.serializer()); }
}
