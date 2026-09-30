package quest.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * `/ws/chat` end to end on a real port: the handshake with both token kinds and both carriers, the frame round trip
 * through the bus, the `clientId` echo that is the ack, typing and read fan-out, the error frame, the rejected
 * handshakes — and a p95 for send → deliver over the in-memory bus, printed for the PR body.
 *
 * <p>The limiter is lifted for this context because the timing run sends more than thirty messages in a minute;
 * the 429 itself is {@code ChatApiTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "quest.chat.messages-per-minute=100000")
class ChatWebSocketTest extends ChatTestSupport {
    @LocalServerPort int port;
    @Autowired ChatSessions sessions;
    @Autowired quest.server.notifications.NotificationService notifications;
    @Autowired quest.server.notifications.NotificationRepository notificationRows;
    @Autowired quest.server.tenancy.StaffScopeRepository staffScopes;
    @Autowired ChatPresence presence;
    @Autowired quest.server.auth.AuthService auth;
    @Autowired quest.server.auth.RefreshTokenService refreshTokens;
    @Autowired quest.server.auth.RefreshTokenRepository tokenRows;

    private String maya;
    private final List<WebSocketSession> open = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach void seed() throws Exception {
        seedSchools();
        maya = child("Maya", "CHSCHA", section1a);
    }

    @AfterEach void clean() {
        open.forEach(s -> { try { s.close(); } catch (Exception ignored) { } });
        notificationRows.deleteAll(notificationRows.findAll().stream().filter(n -> n.getSchoolId().startsWith(prefix())).toList());
        staffScopes.deleteAll(staffScopes.findAll().stream().filter(r -> r.getSchoolId().startsWith(prefix())).toList());
        tokenRows.deleteAll(tokenRows.findAll().stream().filter(t -> t.getUserId().startsWith(prefix())).toList());
        removeSeed();
    }

    // ---------------------------------------------------------------- the handshake

    @Test void a_teacher_connects_with_the_header_and_a_parent_with_the_query_parameter() throws Exception {
        var teacher = new Frames(); var parent = new Frames();
        connect(sara, true, teacher);
        connect(PARENT.substring("Bearer ".length()), false, parent);
        assertThat(sessions.count()).isGreaterThanOrEqualTo(2);
        // and each carrier works for the other kind too
        connect(sara, false, new Frames());
        connect(PARENT.substring("Bearer ".length()), true, new Frames());
    }

    @Test void a_ping_is_answered_with_a_pong() throws Exception {
        var frames = new Frames();
        var session = connect(sara, true, frames);
        session.sendMessage(new TextMessage(PING));
        assertThat(frames.next().get("type").asText()).isEqualTo("pong");
    }

    @Test void a_bad_token_is_401_and_a_parent_with_no_school_on_the_flag_is_403() {
        assertThatThrownBy(() -> connect("admin.not-a-token", true, new Frames())).hasMessageContaining("401");
        assertThatThrownBy(() -> connect("", false, new Frames())).hasMessageContaining("401");
        assertThatThrownBy(() -> connect("fake-token-parent-without-children", false, new Frames())).as("no child in a school with the flag on").hasMessageContaining("403");
    }

    /**
     * D26: the socket is the dashboard's event channel. ADMIN is admitted although no chat has a side for her, and
     * so is a teacher of school B, which has the `chat` flag off — both for the notification frame. What the flag
     * still decides is the chat commands: hers come back as an `error` frame instead of reaching a thread.
     */
    @Test void every_dashboard_role_is_admitted_with_the_chat_flag_off_and_only_the_chat_commands_are_refused() throws Exception {
        connect(adminToken, true, new Frames());
        var flagOff = new Frames();
        var session = connect(other, true, flagOff);
        session.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"body\":\"hi\",\"clientId\":\"c-1\"}"));
        var error = flagOff.next();
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("code").asText()).isEqualTo("forbidden");
        assertThat(error.get("clientId").asText()).isEqualTo("c-1");
        // …and a teacher of a school that has it on still writes as before
        var sara1 = new Frames();
        connect(sara, true, sara1);
    }

    /**
     * E2 (D26): a notification written for a user reaches her sockets as a `notification` frame, and the school on
     * the event is what decides it — a session held by a teacher of school B is not written to for a row that
     * belongs to school A, even though the bus hands every instance every event.
     */
    @Test void a_notification_reaches_its_own_user_and_only_for_its_own_school() throws Exception {
        var schoolB = new Frames();
        connect(other, true, schoolB);
        notifications.notify(A, OTHER, quest.api.dto.NotificationKind.LESSON_READY, "Questions ready", "It is ready.", "/teacher/lessons/x", "x");
        notifications.notify(B, OTHER, quest.api.dto.NotificationKind.LESSON_FAILED, "Generation stopped", "It stopped.", "/teacher/lessons/y", "y");
        var frame = schoolB.next();
        assertThat(frame.get("type").asText()).isEqualTo("notification");
        assertThat(frame.get("notification").get("kind").asText()).as("school A's row was never written to a school B socket").isEqualTo("lesson.failed");
        assertThat(frame.get("notification").get("link").asText()).isEqualTo("/teacher/lessons/y");
        assertThat(schoolB.received).noneMatch(f -> f.contains("lesson.ready"));
    }

    // ---------------------------------------------------------------- frames

    @Test void a_message_reaches_both_sides_and_the_senders_copy_carries_the_client_id() throws Exception {
        var teacher = new Frames(); var parent = new Frames();
        connect(sara, true, teacher);
        var parentSession = connect(PARENT.substring("Bearer ".length()), false, parent);

        parentSession.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"teacherId\":\"" + SARA + "\",\"body\":\"Hello from the app\",\"clientId\":\"c-42\"}"));
        var ack = parent.next();
        assertThat(ack.get("type").asText()).isEqualTo("message");
        assertThat(ack.get("clientId").asText()).as("the sender's copy is the ack").isEqualTo("c-42");
        assertThat(ack.get("message").get("body").asText()).isEqualTo("Hello from the app");
        assertThat(ack.get("message").get("sender").asText()).isEqualTo("parent");
        // T1: the parent's message also writes the teacher a `chat.message` bell row, which arrives first
        assertThat(frameOfType(teacher, "notification").get("notification").get("kind").asText()).isEqualTo("chat.message");
        var delivered = frameOfType(teacher, "message");
        assertThat(delivered.get("message").get("id").asText()).isEqualTo(ack.get("message").get("id").asText());
        assertThat(delivered.has("clientId")).as("nobody else's clientId is echoed").isFalse();
        // it was committed before it was published: REST already has it
        assertThat(parentGet("/children/" + maya + "/chat/threads/" + SARA + "/messages")).hasSize(1);

        // the teacher answers through the same socket, and a REST send is announced on the socket too
        String threadId = ack.get("message").get("threadId").asText();
        open.get(0).sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"body\":\"Hello back\",\"clientId\":\"d-1\"}"));
        assertThat(parent.next().get("message").get("body").asText()).isEqualTo("Hello back");
        assertThat(teacher.next().get("clientId").asText()).isEqualTo("d-1");
        parentPost("/children/" + maya + "/chat/threads/" + SARA + "/messages", send("and via REST", "r-1"));
        assertThat(frameOfType(teacher, "message").get("message").get("body").asText()).isEqualTo("and via REST");
        var own = parent.next();
        assertThat(own.get("message").get("body").asText()).isEqualTo("and via REST");
        assertThat(own.get("clientId").asText()).isEqualTo("r-1");

        // typing fans out to the other side only; read is announced to both
        parentSession.sendMessage(new TextMessage("{\"type\":\"typing\",\"childId\":\"" + maya + "\",\"teacherId\":\"" + SARA + "\"}"));
        var typing = frameOfType(teacher, "typing");
        assertThat(typing.get("threadId").asText()).isEqualTo(threadId);
        assertThat(typing.get("from").asText()).isEqualTo("parent");
        open.get(0).sendMessage(new TextMessage("{\"type\":\"read\",\"childId\":\"" + maya + "\"}"));
        var read = parent.next();
        assertThat(read.get("type").asText()).isEqualTo("read");
        assertThat(read.get("readBy").asText()).isEqualTo("teacher");
        assertThat(frameOfType(teacher, "read")).isNotNull();
        assertThat(parent.received).as("the parent never saw her own typing").noneMatch(f -> f.contains("\"typing\""));
    }

    /**
     * MG1 (DR5): a TEACHER's commands are keyed by child, because her conversations are about one — except on the
     * staff thread she shares with her department manager, which has no child on it at all. A `threadId` she sends is
     * taken as that thread, so a message, a `typing` and a `read` reach the manager over the socket both already hold
     * rather than over a second protocol.
     */
    @Test void a_teacher_talks_to_her_manager_over_the_socket_by_thread_id() throws Exception {
        String managerId = prefix() + "manager", managerToken = manager(managerId);
        String threadId = json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/management/chat/threads").header("Authorization", "Bearer " + managerToken)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"teacherUserId\":\"" + SARA + "\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andReturn()).get("id").asText();

        var teacher = new Frames(); var management = new Frames();
        var teacherSession = connect(sara, true, teacher);
        connect(managerToken, true, management);

        teacherSession.sendMessage(new TextMessage("{\"type\":\"message\",\"threadId\":\"" + threadId + "\",\"body\":\"The plan is in.\",\"clientId\":\"s-1\"}"));
        // T1: the manager connecting told the teacher she is online, so the ack is not the first frame any more
        var online = frameOfType(teacher, "presence");
        assertThat(online.get("online").asBoolean()).isTrue();
        assertThat(online.get("userId").asText()).isEqualTo(managerId);
        var ack = frameOfType(teacher, "message");
        assertThat(ack.get("clientId").asText()).as("the sender's copy is the ack").isEqualTo("s-1");
        // T1: and the message rings her bell as well as landing on her socket — the owner's reported gap
        assertThat(frameOfType(management, "notification").get("notification").get("title").asText()).isEqualTo("Message from Ms Sara");
        var delivered = frameOfType(management, "message");
        assertThat(delivered.get("message").get("body").asText()).isEqualTo("The plan is in.");
        assertThat(delivered.get("message").get("threadId").asText()).isEqualTo(threadId);

        teacherSession.sendMessage(new TextMessage("{\"type\":\"typing\",\"threadId\":\"" + threadId + "\"}"));
        assertThat(frameOfType(management, "typing").get("threadId").asText()).isEqualTo(threadId);

        teacherSession.sendMessage(new TextMessage("{\"type\":\"read\",\"threadId\":\"" + threadId + "\"}"));
        assertThat(frameOfType(management, "read")).isNotNull();
        assertThat(frameOfType(teacher, "read")).as("a read is announced to both sides").isNotNull();

        // A thread that is not hers is refused by the very check the REST route applies; nothing is published.
        teacherSession.sendMessage(new TextMessage("{\"type\":\"read\",\"threadId\":\"not-a-thread-of-hers\"}"));
        assertThat(frameOfType(teacher, "error").get("code").asText()).isEqualTo("not_found");
    }

    /** A MANAGERIAL account of school A with the British department — a `staff_scopes` row with no subject (DR5). */
    private String manager(String userId) {
        var u = users.findById(userId).orElseGet(quest.server.auth.Entities.UserEntity::new);
        u.setId(userId); u.setSchoolId(A); u.setEmail(userId + "@seed.test"); u.setPasswordHash("x");
        u.setRole("MANAGERIAL"); u.setStatus("active"); u.setDisplayName("Ms Nour");
        if (u.getCreatedAt() == null) u.setCreatedAt(java.time.Instant.now());
        u.setUpdatedAt(java.time.Instant.now());
        users.save(u);
        var row = staffScopes.findById(userId + ":british").orElseGet(quest.server.tenancy.Entities.StaffScopeEntity::new);
        row.setId(userId + ":british"); row.setSchoolId(A); row.setUserId(userId); row.setSubject(null); row.setCurriculum("british");
        if (row.getCreatedAt() == null) row.setCreatedAt(java.time.Instant.now());
        staffScopes.save(row);
        return token(userId, "MANAGERIAL", A);
    }

    @Test void a_refused_command_comes_back_as_an_error_frame_with_the_client_id() throws Exception {
        var parent = new Frames();
        var session = connect(PARENT.substring("Bearer ".length()), false, parent);
        String unplaced = unplacedChild("Nobody");
        session.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + unplaced + "\",\"teacherId\":\"" + SARA + "\",\"body\":\"hi\",\"clientId\":\"c-9\"}"));
        var error = parent.next();
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("code").asText()).isEqualTo("child_not_placed");
        assertThat(error.get("clientId").asText()).isEqualTo("c-9");
        session.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"body\":\"no teacher named\"}"));
        assertThat(parent.next().get("code").asText()).isEqualTo("bad_request");
        session.sendMessage(new TextMessage("this is not a frame"));
        assertThat(parent.next().get("code").asText()).isEqualTo("bad_request");
        session.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"teacherId\":\"" + SARA + "\",\"body\":\"" + "x".repeat(2001) + "\"}"));
        assertThat(parent.next().get("code").asText()).isEqualTo("bad_request");
    }

    @Test void a_frame_over_eight_kilobytes_closes_the_socket() throws Exception {
        var parent = new Frames();
        var session = connect(PARENT.substring("Bearer ".length()), false, parent);
        session.sendMessage(new TextMessage("{\"type\":\"ping\",\"pad\":\"" + "p".repeat(9000) + "\"}"));
        assertThat(parent.closed(5)).isNotNull();
        assertThat(parent.closed.getCode()).isEqualTo(CloseStatus.TOO_BIG_TO_PROCESS.getCode());
    }

    // ---------------------------------------------------------------- presence (T1)

    /**
     * T1: the parent shares a thread with Sara, so Sara coming online and going offline is the parent's business —
     * and nobody else's. The frame names her `userId`, and {@link ChatPresence} agrees with it both ways.
     */
    @Test void a_teacher_coming_online_and_going_offline_reaches_the_parent_she_shares_a_thread_with() throws Exception {
        var parent = new Frames();
        var parentSession = connect(PARENT.substring("Bearer ".length()), false, parent);
        parentSession.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"teacherId\":\"" + SARA + "\",\"body\":\"hello\"}"));
        assertThat(frameOfType(parent, "message")).isNotNull();

        var teacher = new Frames();
        var teacherSession = connect(sara, true, teacher);
        var arrived = frameOfType(parent, "presence");
        assertThat(arrived.get("online").asBoolean()).isTrue();
        assertThat(arrived.get("userId").asText()).isEqualTo(SARA);
        assertThat(arrived.has("parentId")).as("a dashboard user is named by userId alone").isFalse();
        assertThat(presence.userOnline(SARA)).isTrue();

        teacherSession.close();
        var left = frameOfType(parent, "presence");
        assertThat(left.get("online").asBoolean()).isFalse();
        assertThat(left.get("userId").asText()).isEqualTo(SARA);
        await(() -> !presence.userOnline(SARA));
        assertThat(presence.userOnline(SARA)).as("the last socket of hers has gone").isFalse();
    }

    /**
     * T1, the owner's second bug: a logged-out manager must not go on showing "Live". Revoking her refresh token —
     * what `POST /auth/sign-out` does — closes every socket of hers, which is what ends her presence even when the
     * dashboard never got the chance to close it itself.
     */
    @Test void signing_out_closes_her_socket_and_ends_her_presence() throws Exception {
        var teacher = new Frames();
        connect(sara, true, teacher);
        assertThat(presence.userOnline(SARA)).isTrue();

        auth.signOut(refreshTokens.issue(SARA));
        assertThat(teacher.closed(5)).as("the socket is closed, not left open on a dead session").isNotNull();
        assertThat(teacher.closed.getReason()).isEqualTo("signed out");
        await(() -> !presence.userOnline(SARA));
        assertThat(presence.userOnline(SARA)).isFalse();
    }

    /** The first frame of a kind, skipping the ones a live socket also carries (a `read`, a `message`). */
    private static JsonNode frameOfType(Frames frames, String type) throws Exception {
        for (int i = 0; i < 10; i++) { var frame = frames.next(); if (type.equals(frame.get("type").asText())) return frame; }
        throw new AssertionError("no " + type + " frame arrived");
    }

    /** Presence is settled on the bus thread, so the assertion polls rather than reads once. */
    private static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (int i = 0; i < 100 && !done.getAsBoolean(); i++) Thread.sleep(50);
    }

    // ---------------------------------------------------------------- performance

    /** Send → deliver across the in-memory bus, 200 messages; the number is the PR body's p95. */
    @Test void send_to_deliver_p95_is_under_fifty_milliseconds_locally() throws Exception {
        var teacher = new Frames(); var parent = new Frames();
        connect(sara, true, teacher);
        var parentSession = connect(PARENT.substring("Bearer ".length()), false, parent);
        int n = 200;
        for (int i = 0; i < n; i++) {
            String body = "p" + i;
            SENT.put(body, System.nanoTime());
            parentSession.sendMessage(new TextMessage("{\"type\":\"message\",\"childId\":\"" + maya + "\",\"teacherId\":\"" + SARA + "\",\"body\":\"" + body + "\"}"));
            frameOfType(teacher, "message");                                    // one at a time: latency, not throughput
        }
        var latencies = new ArrayList<Long>(teacher.receivedAt.values());
        assertThat(latencies).hasSize(n);
        Collections.sort(latencies);
        long p50 = latencies.get(n / 2) / 1_000_000, p95 = latencies.get((int) (n * 0.95)) / 1_000_000, max = latencies.getLast() / 1_000_000;
        System.out.printf("chat send->deliver over %d messages: p50 %d ms, p95 %d ms, max %d ms%n", n, p50, p95, max);
        assertThat(p95).as("p95 send→deliver on H2 with the in-memory bus").isLessThan(50);
    }

    // ---------------------------------------------------------------- the client

    /** What a client sees: the frames, in order, ping frames set aside, and the close status. */
    static final class Frames extends TextWebSocketHandler {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Long> receivedAt = new ConcurrentHashMap<>();
        volatile CloseStatus closed;

        @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            String payload = message.getPayload();
            received.add(payload);
            if (payload.contains("\"type\":\"ping\"")) return;
            int at = payload.indexOf("\"body\":\"p");
            if (at > 0) { String body = payload.substring(at + 8, payload.indexOf('"', at + 8)); receivedAt.put(body, System.nanoTime() - SENT.getOrDefault(body, System.nanoTime())); }
            queue.add(payload);
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { closed = status; }

        JsonNode next() throws Exception {
            String payload = queue.poll(5, TimeUnit.SECONDS);
            assertThat(payload).as("a frame within five seconds").isNotNull();
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload);
        }
        CloseStatus closed(int seconds) throws InterruptedException {
            for (int i = 0; i < seconds * 20 && closed == null; i++) Thread.sleep(50);
            return closed;
        }
    }

    /** Sent-at stamps the timing run shares with its handler; keyed by body, which the run makes unique. */
    static final Map<String, Long> SENT = new ConcurrentHashMap<>();

    /**
     * Connects and does not return until the server holds the socket. The client's handshake future completes as
     * soon as the upgrade is answered, which is before the container has run `afterConnectionEstablished` and the
     * session is in {@link ChatSessions} — so {@code sessions.count()}, or a broadcast aimed at this peer, read
     * straight after it is a race, and that is what made this class flake. A `ping` answered with a `pong` is the
     * proof: the container delivers a frame to a session only once that session's `afterConnectionEstablished`
     * has returned, so the `pong` cannot arrive before the registration it depends on.
     */
    private WebSocketSession connect(String token, boolean viaHeader, Frames frames) throws Exception {
        var headers = new WebSocketHttpHeaders();
        String url = "ws://localhost:" + port + "/ws/chat";
        if (viaHeader) headers.add("Authorization", "Bearer " + token);
        else url += "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        WebSocketSession session;
        try { session = new StandardWebSocketClient().execute(frames, headers, URI.create(url)).get(5, TimeUnit.SECONDS); }
        catch (ExecutionException e) { throw new IllegalStateException(e.getCause().getMessage(), e.getCause()); }
        open.add(session);
        session.sendMessage(new TextMessage(PING));
        assertThat(frames.next().get("type").asText()).as("the server has registered the socket").isEqualTo("pong");
        return session;
    }

    private static final String PING = "{\"type\":\"ping\"}";
}
