package quest.server.chat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One process, one queue: the H2 profiles and the test suite. Delivery is asynchronous and in order, on one thread,
 * so the calling shape is the same as the PostgreSQL bus's and a test cannot pass by accident of synchrony.
 */
public final class InMemoryChatBus implements ChatBus {
    private static final Logger log = LoggerFactory.getLogger(InMemoryChatBus.class);
    private final List<Consumer<ChatEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ExecutorService dispatcher = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "chat-bus-memory"); t.setDaemon(true); return t; });

    @Override public void publish(ChatEvent event) {
        dispatcher.execute(() -> listeners.forEach(l -> { try { l.accept(event); } catch (RuntimeException e) { log.warn("chat listener failed on {}: {}", event.kind(), e.toString()); } }));
    }
    @Override public void subscribe(Consumer<ChatEvent> listener) { listeners.add(listener); }
    @Override public void close() { dispatcher.shutdownNow(); }
}
