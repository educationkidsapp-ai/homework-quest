package quest.server.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The sockets this instance holds, by the user behind each (`parent:<id>` / `teacher:<id>`; one user may hold
 * several — a phone and a tablet). Everything that leaves goes through {@link Live#offer}, which is the
 * backpressure rule of the brief in one place:
 *
 * <ul>
 *   <li>frames are written by one virtual thread per socket, in order, so a slow peer never blocks the bus thread
 *       that delivers to everybody else;</li>
 *   <li>a <em>droppable</em> frame (typing, the heartbeat) is skipped while anything is still queued for that
 *       socket — a peer that cannot keep up does not need to know who is typing;</li>
 *   <li>a message is never dropped: it is queued until the queue passes {@code sendBufferBytes}, or one write has
 *       taken longer than {@code sendTimeout}, and then the socket is closed 1008 — the client reconnects and
 *       refetches with `?since=`, which is the delivery guarantee the contract actually makes.</li>
 * </ul>
 *
 * <p>The sweep runs every {@code heartbeatSeconds}: it sends `{"type":"ping"}` to every socket and closes 1000
 * "idle" the ones that sent nothing — not even a `pong` — for {@code idleSeconds}. Per instance, like every other
 * sweep here; the sockets are per instance too.
 */
@Component
public class ChatSessions {
    private static final Logger log = LoggerFactory.getLogger(ChatSessions.class);
    static final String PING = "{\"type\":\"ping\"}";
    static final CloseStatus IDLE = CloseStatus.NORMAL.withReason("idle");
    static final CloseStatus STUCK = CloseStatus.SESSION_NOT_RELIABLE.withReason("send buffer over limit");

    /**
     * Who is on the other end, as the handshake resolved it. `schoolId` is the dashboard user's — a parent has
     * none, and neither has the platform ADMIN, who is cross-school by design (D6). `chat` is D26's split: every
     * dashboard role is admitted to the socket for notifications, but only a peer with `chat` true may send a
     * chat command, which is the `chat` flag and the TEACHER/parent rule the REST half keeps.
     */
    public record Peer(String key, String role, String id, String schoolId, Object principal, boolean chat) {}

    private final Map<String, Set<Live>> byKey = new ConcurrentHashMap<>();
    private final Map<String, Live> byId = new ConcurrentHashMap<>();
    private final Clock clock; private final long bufferLimit; private final Duration sendTimeout, idle;

    public ChatSessions(Clock clock, @Value("${quest.chat.send-buffer-bytes:65536}") long bufferLimit,
                        @Value("${quest.chat.send-timeout-seconds:10}") long sendTimeoutSeconds, @Value("${quest.chat.idle-seconds:600}") long idleSeconds) {
        this.clock = clock; this.bufferLimit = bufferLimit; this.sendTimeout = Duration.ofSeconds(sendTimeoutSeconds); this.idle = Duration.ofSeconds(idleSeconds);
    }

    public Live register(WebSocketSession session, Peer peer) {
        var live = new Live(session, peer, clock.instant());
        byId.put(session.getId(), live);
        byKey.computeIfAbsent(peer.key(), k -> ConcurrentHashMap.newKeySet()).add(live);
        return live;
    }

    public void remove(WebSocketSession session) { var live = byId.remove(session.getId()); if (live != null) forget(live); }

    /** Any frame from the client — a command, a `pong` — resets its idle clock. */
    public void touch(WebSocketSession session) { var live = byId.get(session.getId()); if (live != null) live.lastInbound = clock.instant(); }

    public Live of(WebSocketSession session) { return byId.get(session.getId()); }

    /** Sends a frame to every socket of a user: queued, never dropped; a stuck socket is closed instead. */
    public void send(String key, String frame) { for (var live : sessions(key)) live.offer(frame, false); }

    /**
     * D26: a frame for one person, written only to the sessions that belong to the named school. A session with no
     * school of its own — the platform ADMIN's — is never filtered out: she reads across schools, and the frame was
     * addressed to her id in the first place.
     */
    public void sendToSchool(String key, String schoolId, String frame) {
        for (var live : sessions(key)) if (live.peer.schoolId() == null || live.peer.schoolId().equals(schoolId)) live.offer(frame, false);
    }

    /** Sends a droppable frame (typing): skipped on any socket that still has frames queued. */
    public void sendDroppable(String key, String frame) { for (var live : sessions(key)) live.offer(frame, true); }

    public int count() { return byId.size(); }

    @Scheduled(fixedDelayString = "${quest.chat.heartbeat-seconds:30}s", initialDelayString = "${quest.chat.heartbeat-seconds:30}s")
    public void heartbeat() { sweep(clock.instant()); }

    /** One pass: close the idle and the stuck, ping the rest. Returns how many were closed. */
    public int sweep(Instant now) {
        int closed = 0;
        for (var live : new ArrayList<>(byId.values())) {
            long inflight = live.inflightSince;
            if (inflight != 0 && now.toEpochMilli() - inflight > sendTimeout.toMillis()) { live.terminate(STUCK); closed++; }
            else if (Duration.between(live.lastInbound, now).compareTo(idle) > 0) { live.terminate(IDLE); closed++; }
            else live.offer(PING, true);
        }
        return closed;
    }

    private List<Live> sessions(String key) { var set = byKey.get(key); return set == null ? List.of() : new ArrayList<>(set); }

    private void forget(Live live) {
        byId.remove(live.session.getId());
        var set = byKey.get(live.peer.key());
        if (set != null) { set.remove(live); if (set.isEmpty()) byKey.remove(live.peer.key(), set); }
        live.sender.shutdown();
    }

    /** One socket: its peer, its ordered sender and the numbers the backpressure rule reads. */
    public final class Live {
        final WebSocketSession session; final Peer peer;
        private final ExecutorService sender;
        private final AtomicLong pending = new AtomicLong();
        volatile long inflightSince; volatile Instant lastInbound; private volatile boolean closing;

        Live(WebSocketSession session, Peer peer, Instant now) {
            this.session = session; this.peer = peer; this.lastInbound = now;
            this.sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("chat-send-" + session.getId()).factory());
        }

        public Peer peer() { return peer; }
        public long pendingBytes() { return pending.get(); }

        /** Queues a frame; false when it was dropped (droppable and something is queued) or the socket was closed for it. */
        public boolean offer(String frame, boolean droppable) {
            if (closing) return false;
            long len = frame.getBytes(StandardCharsets.UTF_8).length;
            if (droppable && pending.get() > 0) return false;
            if (pending.addAndGet(len) > bufferLimit) { pending.addAndGet(-len); terminate(STUCK); return false; }
            try {
                sender.execute(() -> {
                    inflightSince = clock.instant().toEpochMilli();
                    try { if (!closing) session.sendMessage(new TextMessage(frame)); }
                    catch (IOException | RuntimeException e) { log.debug("chat: send to {} failed ({}); closing", peer.key(), e.toString()); terminate(CloseStatus.SESSION_NOT_RELIABLE); }
                    finally { inflightSince = 0; pending.addAndGet(-len); }
                });
            } catch (RejectedExecutionException gone) { pending.addAndGet(-len); return false; }
            return true;
        }

        /** Closes on a thread of its own: a close frame to a stuck peer can block as long as any other write. */
        public void terminate(CloseStatus status) {
            if (closing) return;
            closing = true;
            forget(this);
            Thread.startVirtualThread(() -> { try { session.close(status); } catch (IOException | RuntimeException e) { log.debug("chat: close of {} failed: {}", peer.key(), e.toString()); } });
        }
    }
}
