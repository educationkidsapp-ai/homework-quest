package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * The backpressure rule of the brief, on a socket that stops reading: typing is dropped, a message is never
 * dropped, and a peer that lets the queue pass the limit is closed rather than starved. Plus the sweep: idle
 * sockets are closed, live ones pinged.
 */
class ChatSessionsTest {
    private static final Instant T0 = Instant.parse("2026-09-21T10:00:00Z");
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
    private final ChatSessions.Peer peer = new ChatSessions.Peer("parent:p1", "parent", "p1", null, null, true);

    private static WebSocketSession session(String id) { var s = mock(WebSocketSession.class); when(s.getId()).thenReturn(id); return s; }

    /** The write's `finally` runs after Mockito has seen the call, so the count is polled rather than read once. */
    private static boolean drained(ChatSessions.Live live) throws InterruptedException {
        for (int i = 0; i < 200 && live.pendingBytes() != 0; i++) Thread.sleep(10);
        return live.pendingBytes() == 0;
    }

    @Test void typing_is_dropped_while_a_send_is_stuck_but_a_message_is_queued() throws Exception {
        var sessions = new ChatSessions(clock, 1024, 10, 600);
        var stuck = session("s1");
        var writing = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> { writing.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(stuck).sendMessage(any());
        var live = sessions.register(stuck, peer);

        assertThat(live.offer("first", false)).isTrue();
        assertThat(writing.await(2, TimeUnit.SECONDS)).as("the first frame is being written").isTrue();
        assertThat(live.offer("{\"type\":\"typing\"}", true)).as("typing is dropped while anything is queued").isFalse();
        assertThat(live.offer("second message", false)).as("a message is never dropped").isTrue();
        assertThat(live.pendingBytes()).isEqualTo("first".length() + "second message".length());
        release.countDown();
        verify(stuck, timeout(2000).times(2)).sendMessage(any());
        assertThat(drained(live)).as("the queue empties once the writes return").isTrue();
        verify(stuck, never()).close(any());
    }

    @Test void a_socket_whose_queue_passes_the_limit_is_closed_not_starved() throws Exception {
        var sessions = new ChatSessions(clock, 100, 10, 600);
        var stuck = session("s2");
        var release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(stuck).sendMessage(any());
        var live = sessions.register(stuck, peer);
        assertThat(live.offer("x".repeat(60), false)).isTrue();
        assertThat(live.offer("y".repeat(60), false)).as("120 bytes queued against a limit of 100").isFalse();
        verify(stuck, timeout(2000)).close(ChatSessions.STUCK);
        assertThat(sessions.count()).as("gone from the registry the moment it is closed").isZero();
        assertThat(live.offer("late", false)).isFalse();
        release.countDown();
    }

    @Test void the_sweep_closes_the_idle_and_pings_the_rest() throws Exception {
        var sessions = new ChatSessions(clock, 65536, 10, 600);
        var idle = session("idle"); var live = session("live");
        sessions.register(idle, peer);
        sessions.register(live, new ChatSessions.Peer("user:t1", "teacher", "t1", "school", null, true));
        sessions.touch(live);                                                   // heard from at T0, like `idle`
        int closed = sessions.sweep(T0.plus(Duration.ofMinutes(11)));
        assertThat(closed).isEqualTo(2);                                        // both were last heard from at T0
        verify(idle, timeout(2000)).close(ChatSessions.IDLE);
        verify(live, timeout(2000)).close(ChatSessions.IDLE);

        // a registry whose clock reads T0 + 5 min: a socket registered now is four minutes old at T0 + 9 and is pinged, not closed
        var later = new ChatSessions(Clock.fixed(T0.plus(Duration.ofMinutes(5)), ZoneOffset.UTC), 65536, 10, 600);
        var fresh = session("fresh");
        var stillThere = later.register(fresh, peer);
        assertThat(later.sweep(T0.plus(Duration.ofMinutes(9)))).isZero();
        verify(fresh, timeout(2000)).sendMessage(new TextMessage(ChatSessions.PING));
        assertThat(drained(stillThere)).isTrue();
        verify(fresh, never()).close(any(CloseStatus.class));
    }

    @Test void a_write_that_outlives_the_send_timeout_closes_the_socket() throws Exception {
        var sessions = new ChatSessions(clock, 65536, 10, 600);
        var slow = session("slow");
        var writing = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(inv -> { writing.countDown(); release.await(5, TimeUnit.SECONDS); return null; }).when(slow).sendMessage(any());
        sessions.register(slow, peer);
        sessions.send("parent:p1", "hello");
        assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(sessions.sweep(T0.plus(Duration.ofSeconds(11)))).isEqualTo(1);
        verify(slow, timeout(2000)).close(ChatSessions.STUCK);
        release.countDown();
    }
}
