package quest.server.chat;

import java.util.function.Consumer;

/**
 * Fan-out between the instances that hold sockets. A message is committed before it is published, and every
 * instance — the publishing one included — hears it the same way, so there is exactly one delivery path.
 * {@link PostgresChatBus} rides on `LISTEN/NOTIFY` when the datasource is PostgreSQL; {@link InMemoryChatBus} is the
 * H2 stand-in. `ChatBusConfig` picks one from the datasource, or from `quest.chat.bus`.
 */
public interface ChatBus extends AutoCloseable {
    void publish(ChatEvent event);
    void subscribe(Consumer<ChatEvent> listener);
    @Override default void close() {}
}
