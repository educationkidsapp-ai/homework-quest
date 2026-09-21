package quest.server.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import quest.api.dto.ChatFrame;
import quest.api.dto.ChatMessage;
import quest.server.config.Json;

/**
 * The bus's one subscriber on each instance: turns a {@link ChatEvent} into frames for the two parties' sockets held
 * here. The sender's own sessions get the message too — with the {@code clientId} it was sent with, which is the ack
 * — so a parent's second device and the dashboard tab beside the one that typed all see the same thread.
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
        String teacherKey = ChatService.key(ChatService.TEACHER, e.teacherId());
        String parentKey = e.parentId() == null ? null : ChatService.key(ChatService.PARENT, e.parentId());
        switch (e.kind()) {
            case ChatEvent.MESSAGE -> {
                ChatMessage message = message(e);
                if (message == null) return;
                for (String key : new String[] {teacherKey, parentKey}) {
                    if (key == null) continue;
                    boolean own = key.equals(e.senderKey());
                    sessions.send(key, encode(new ChatFrame.Message(message, own ? e.clientId() : null)));
                }
            }
            case ChatEvent.READ -> {
                String frame = encode(new ChatFrame.Read(e.threadId(), ChatService.sender(e.sender()), e.at()));
                sessions.send(teacherKey, frame);
                if (parentKey != null) sessions.send(parentKey, frame);
            }
            case ChatEvent.TYPING -> {
                String other = ChatService.PARENT.equals(e.sender()) ? teacherKey : parentKey;
                if (other != null) sessions.sendDroppable(other, encode(new ChatFrame.Typing(e.threadId(), ChatService.sender(e.sender()))));
            }
            default -> log.warn("chat: unknown event kind {}", e.kind());
        }
    }

    /** The message as published, or loaded by id when the `NOTIFY` payload could not carry it. */
    private ChatMessage message(ChatEvent e) {
        if (e.messageJson() != null) return json.decodeShared(e.messageJson(), ChatMessage.Companion.serializer());
        return messages.findOneById(e.messageId()).map(ChatService::dto).orElse(null);
    }

    String encode(ChatFrame frame) { return json.encodeShared(frame, ChatFrame.Companion.serializer()); }
}
