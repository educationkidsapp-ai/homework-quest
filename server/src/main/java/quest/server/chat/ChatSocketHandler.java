package quest.server.chat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import quest.api.dto.ChatCommand;
import quest.api.dto.ChatFrame;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.tenancy.CoordinatorScope;
import quest.server.tenancy.TenantContext;

/**
 * `/ws/chat`: plain JSON text frames, no STOMP. A command is decoded with the shared codec, run through
 * {@link ChatService} exactly as the REST call would be — inside the teacher's tenant scope, because a socket
 * thread has no request and therefore no filter until one is set — and answered with an `error` frame when it is
 * refused. A coordinator's commands name a thread rather than a child (R4), because one of her threads has no child
 * on it; everything else about them is the teacher's path. Nothing is answered directly otherwise: the message comes back through the bus like everyone else's,
 * carrying the `clientId` it was sent with.
 */
@Component
public class ChatSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatSocketHandler.class);
    static final int MAX_FRAME_BYTES = 8 * 1024;

    private final ChatService chat; private final ChatSessions sessions; private final ChatHub hub; private final TenantContext tenant;
    private final Json json; private final ChatPresence presence;

    public ChatSocketHandler(ChatService chat, ChatSessions sessions, ChatHub hub, TenantContext tenant, Json json, ChatPresence presence) {
        this.chat = chat; this.sessions = sessions; this.hub = hub; this.tenant = tenant; this.json = json; this.presence = presence;
    }

    @Override public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(MAX_FRAME_BYTES); session.setBinaryMessageSizeLimit(1);
        var peer = (ChatSessions.Peer) session.getAttributes().get(ChatHandshake.PEER);
        if (peer == null) { close(session, CloseStatus.POLICY_VIOLATION); return; }
        sessions.register(session, peer);
        presence.arrived(peer);
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        sessions.touch(session);
        var live = sessions.of(session);
        if (live == null) return;
        // T1 (review): the `pong` that keeps the socket alive also renews her presence lease on the other instances.
        presence.refresh(live.peer());
        ChatCommand command;
        try { command = json.decodeShared(message.getPayload(), ChatCommand.Companion.serializer()); }
        catch (RuntimeException e) { live.offer(hub.encode(new ChatFrame.Error("bad_request", "Unreadable frame.", null)), false); return; }
        String clientId = command instanceof ChatCommand.Send s ? s.getClientId() : null;
        try { scoped(live.peer(), command, () -> run(live.peer(), command, live)); }
        catch (ApiException e) { live.offer(hub.encode(new ChatFrame.Error(e.error().code(), e.error().message(), clientId)), false); }
        catch (RuntimeException e) { log.error("chat: command failed for {}", live.peer().key(), e); live.offer(hub.encode(new ChatFrame.Error("internal", "Something went wrong on the server.", clientId)), false); }
    }

    private void run(ChatSessions.Peer peer, ChatCommand command, ChatSessions.Live live) {
        boolean parent = ChatService.PARENT.equals(peer.role());
        // R4, RM2: a coordinator, a manager and an admin all name a thread by id — one of their threads has no child.
        boolean byThread = CoordinatorScope.ROLE.equalsIgnoreCase(peer.role())
                || quest.server.tenancy.ManagerScope.ROLE.equalsIgnoreCase(peer.role()) || "ADMIN".equalsIgnoreCase(peer.role());
        switch (command) {
            case ChatCommand.Send s -> {
                chatOnly(peer);
                if (parent) chat.parentSend((Principals.Parent) peer.principal(), required(s.getChildId(), "childId"), required(s.getTeacherId(), "teacherId"), s.getBody(), s.getAttachmentIds(), s.getClientId(), null);
                else if (byThread) chat.staffSend((Principals.User) peer.principal(), required(s.getThreadId(), "threadId"), s.getBody(), s.getAttachmentIds(), s.getClientId());
                else if (named(s.getThreadId())) chat.teacherStaffSend((Principals.User) peer.principal(), s.getThreadId(), s.getBody(), s.getAttachmentIds(), s.getClientId());
                else chat.teacherSend((Principals.User) peer.principal(), required(s.getChildId(), "childId"), s.getBody(), s.getAttachmentIds(), s.getClientId());
            }
            case ChatCommand.Read r -> {
                chatOnly(peer);
                if (parent) chat.parentRead((Principals.Parent) peer.principal(), required(r.getChildId(), "childId"), required(r.getTeacherId(), "teacherId"));
                else if (byThread) chat.staffRead((Principals.User) peer.principal(), required(r.getThreadId(), "threadId"));
                else if (named(r.getThreadId())) chat.teacherStaffRead((Principals.User) peer.principal(), r.getThreadId());
                else chat.teacherRead((Principals.User) peer.principal(), required(r.getChildId(), "childId"));
            }
            case ChatCommand.Typing t -> {
                chatOnly(peer);
                if (parent) chat.parentTyping((Principals.Parent) peer.principal(), required(t.getChildId(), "childId"), required(t.getTeacherId(), "teacherId"));
                else if (byThread) chat.staffTyping((Principals.User) peer.principal(), required(t.getThreadId(), "threadId"));
                else if (named(t.getThreadId())) chat.teacherStaffTyping((Principals.User) peer.principal(), t.getThreadId());
                else chat.teacherTyping((Principals.User) peer.principal(), required(t.getChildId(), "childId"));
            }
            case ChatCommand.Ping p -> live.offer(hub.encode(ChatFrame.Pong.INSTANCE), false);
            case ChatCommand.Pong p -> { }
        }
    }

    /**
     * D26: the socket admits every dashboard role so notifications have somewhere to land, but the chat commands
     * stay gated exactly as chat REST is — the `chat` flag on, and a TEACHER or a parent behind them. Anyone else
     * gets the same `forbidden` error frame any other refusal produces.
     */
    private static void chatOnly(ChatSessions.Peer peer) {
        if (!peer.chat() && !admin(peer)) throw ApiException.forbidden("Chat is not available for this account.");
    }

    /**
     * A teacher's commands run with her school's filter on, as her requests do; a parent has no scope, as ever.
     *
     * <p>B5: the Admin's token names no school, so until now every chat command of hers was `forbidden` on the socket —
     * her `typing` included, which is why a parent never saw her typing (the owner answers parents as the Admin). Her
     * commands name a thread, and the thread's school is now her scope, exactly as `X-School-Id` is over REST: a thread
     * she is not on is `not_found`, and the `chat` flag of that school is checked by the service as for her REST writes.
     */
    private void scoped(ChatSessions.Peer peer, ChatCommand command, Runnable work) {
        String header = null;
        if (peer.schoolId() == null && admin(peer)) {
            String threadId = switch (command) {
                case ChatCommand.Send s -> s.getThreadId(); case ChatCommand.Read r -> r.getThreadId(); case ChatCommand.Typing t -> t.getThreadId();
                default -> null; };
            if (named(threadId)) header = chat.adminThreadSchool((Principals.User) peer.principal(), threadId);
        }
        if (peer.schoolId() == null && header == null) { work.run(); return; }
        tenant.set(peer.role().toUpperCase(java.util.Locale.ROOT), peer.schoolId(), header);
        try { work.run(); } finally { tenant.clear(); }
    }

    private static boolean admin(ChatSessions.Peer peer) { return "ADMIN".equalsIgnoreCase(peer.role()); }

    /**
     * MG1: a TEACHER's commands are keyed by child, because her conversations are about one — <em>except</em> on the
     * staff thread she holds with her department manager, which has no child on it at all. So a `threadId` she sends
     * is taken as that thread (and refused by {@code ChatService.ownTeacherThread} when it is not hers), and a command
     * with neither field still asks for `childId`, as it always did.
     */
    private static boolean named(String value) { return value != null && !value.isBlank(); }

    /** A command names what its sender's half of the chat is keyed by: a child, a teacher, or — R4 — a thread. */
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(field + " is required");
        return value;
    }

    /**
     * T1: the peer comes off the attributes rather than out of {@link ChatSessions}, because a socket closed by the
     * sweep or by a sign-out was already forgotten there — and this is the one path every close runs through, so it
     * is where "she went offline" has to be decided.
     */
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        var peer = (ChatSessions.Peer) session.getAttributes().get(ChatHandshake.PEER);
        if (peer != null) presence.left(peer);
    }

    @Override public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("chat: transport error on {}: {}", session.getId(), exception.toString());
        sessions.remove(session);
        close(session, CloseStatus.SESSION_NOT_RELIABLE);
    }

    private static void close(WebSocketSession session, CloseStatus status) { try { session.close(status); } catch (Exception ignored) { } }
}
