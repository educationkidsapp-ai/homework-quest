package quest.server.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `LISTEN chat_events` on one dedicated connection per instance, `NOTIFY` from a pooled one per publish. Cloud Run
 * runs up to two instances of the API and a socket lives on whichever took the handshake; a message sent through
 * the other one reaches it this way, within {@code pollMillis} of the commit.
 *
 * <p><strong>The listener connection.</strong> Borrowed from the pool once and held for the life of the bean
 * (Hikari never evicts a connection that is out on loan), so the pool is one connection smaller than configured.
 * `getNotifications(timeout)` blocks on the socket for at most {@code pollMillis}, so a notification is handed on
 * without a sleep in between. When the connection breaks — a Cloud SQL restart, a network blip — the loop logs it,
 * waits a second and borrows a fresh one; nothing published in between is replayed, which is what the client's
 * `?since=` refetch on reconnect is for and why the contract never promises the socket alone is complete.
 *
 * <p><strong>The payload.</strong> `NOTIFY` carries at most 8 000 bytes. An event whose encoded message would not fit
 * (a 2 000-character body in a four-byte script) goes out with {@code messageJson} dropped and {@link ChatHub}
 * loads the row by id.
 */
public final class PostgresChatBus implements ChatBus {
    static final String CHANNEL = "chat_events";
    /** Under the 8 000-byte `NOTIFY` limit with room for the envelope. */
    static final int MAX_PAYLOAD_BYTES = 7_000;
    private static final Logger log = LoggerFactory.getLogger(PostgresChatBus.class);

    private final DataSource dataSource; private final ObjectMapper mapper; private final int pollMillis;
    private final List<Consumer<ChatEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Thread listener;
    private volatile boolean running = true;

    public PostgresChatBus(DataSource dataSource, ObjectMapper mapper, int pollMillis) {
        this.dataSource = dataSource; this.mapper = mapper; this.pollMillis = Math.max(50, pollMillis);
        this.listener = new Thread(this::listen, "chat-bus-listen");
        this.listener.setDaemon(true);
    }

    /** Starts the listener; separate from the constructor so a test can build two buses on one database. */
    public PostgresChatBus start() { listener.start(); return this; }

    @Override public void publish(ChatEvent event) {
        String payload = encode(event);
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) payload = encode(event.withoutMessageJson());
        try (Connection c = dataSource.getConnection(); var ps = c.prepareStatement("select pg_notify(?, ?)")) {
            ps.setString(1, CHANNEL); ps.setString(2, payload); ps.execute();
        } catch (Exception e) { log.error("chat: NOTIFY failed for {} in thread {}: {}", event.kind(), event.threadId(), e.toString()); }
    }

    @Override public void subscribe(Consumer<ChatEvent> listener) { listeners.add(listener); }

    @Override public void close() { running = false; listener.interrupt(); }

    private void listen() {
        while (running) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(true);
                try (var st = c.createStatement()) { st.execute("LISTEN " + CHANNEL); }
                var pg = c.unwrap(PGConnection.class);
                log.info("chat: listening on {}", CHANNEL);
                while (running) {
                    PGNotification[] notes = pg.getNotifications(pollMillis);
                    if (notes == null) continue;
                    for (var n : notes) dispatch(n.getParameter());
                }
            } catch (Exception e) {
                if (!running) return;
                log.warn("chat: listener connection lost ({}); reconnecting", e.toString());
                try { Thread.sleep(1_000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void dispatch(String payload) {
        ChatEvent event;
        try { event = mapper.readValue(payload, ChatEvent.class); } catch (Exception e) { log.warn("chat: unreadable notification dropped: {}", e.toString()); return; }
        for (var l : listeners) { try { l.accept(event); } catch (RuntimeException e) { log.warn("chat listener failed on {}: {}", event.kind(), e.toString()); } }
    }

    private String encode(ChatEvent event) { try { return mapper.writeValueAsString(event); } catch (Exception e) { throw new IllegalStateException(e); } }
}
