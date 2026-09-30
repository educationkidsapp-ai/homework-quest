package quest.server.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import quest.api.dto.ChatFrame;
import quest.server.auth.SessionsRevoked;
import quest.server.children.ChildRepository;
import quest.server.config.Json;

/**
 * T1: <strong>who is online.</strong> One question — "does this person hold a live `/ws/chat` socket?" — answered
 * from {@link ChatSessions} for the sockets this instance holds and from a `presence` event on the {@link ChatBus}
 * for the ones another instance holds. It is the single source for {@code ChatThread.peerOnline}, for
 * {@code StaffContact.online} and for the `presence` frame, so a thread row and a directory row never disagree.
 *
 * <p><strong>Why an event and not a table.</strong> Presence is worth exactly as much as the socket it describes and
 * nothing is replayed after a missed frame, which is the rule the whole socket keeps; a row in PostgreSQL would
 * outlive the connection it claimed and be wrong in the one direction that matters (the owner's bug: a manager who
 * signed out still shown as "Live"). So a connect and a disconnect publish, every instance keeps what it heard in
 * memory, and the entry expires after {@code presence-ttl-seconds} in case an instance dies without saying goodbye.
 * Two hours is a safe default because Cloud Run ends every socket at the request timeout (one hour): a connection
 * that is genuinely alive re-publishes long before its entry fades.
 *
 * <p><strong>Going offline actually happens.</strong> Three things end a session, and all three run through
 * {@link ChatSessions}: the socket closing (the tab, a navigation, the hourly Cloud Run cut), the heartbeat sweep
 * closing one that answered no `pong` for two intervals, and — {@link #signedOut} — a revoked refresh token, which
 * is `POST /auth/sign-out` and a password change. The dashboard closing its socket on logout is the fast path; this
 * is the one that holds when it does not.
 */
@Component
public class ChatPresence {
    private final ChatSessions sessions; private final ChatThreadRepository threads; private final ChildRepository children;
    private final ChatBus bus; private final Json json; private final Clock clock; private final Duration ttl;

    /** What another instance told us, and until when. Keys are session keys, as {@link ChatService#key} builds them. */
    private final Map<String, Instant> elsewhere = new ConcurrentHashMap<>();

    public ChatPresence(ChatSessions sessions, ChatThreadRepository threads, ChildRepository children, ChatBus bus, Json json,
                        Clock clock, @Value("${quest.chat.presence-ttl-seconds:7200}") long ttlSeconds) {
        this.sessions = sessions; this.threads = threads; this.children = children; this.bus = bus; this.json = json;
        this.clock = clock; this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Online iff a socket of hers is held here, or another instance said so and has not taken it back. */
    public boolean online(String key) {
        if (key == null || key.isBlank()) return false;
        if (sessions.holds(key)) return true;
        var until = elsewhere.get(key);
        if (until == null) return false;
        if (until.isBefore(clock.instant())) { elsewhere.remove(key); return false; }
        return true;
    }

    public boolean userOnline(String userId) { return userId != null && online(ChatService.key(ChatService.USER, userId)); }

    /** Her first socket on this instance: the others are told. A second tab changes nothing anyone can see. */
    public void arrived(ChatSessions.Peer peer) { if (sessions.countFor(peer.key()) == 1) publish(peer, true); }

    /** Her last socket here has gone: offline, unless another instance still holds one (its own event says so). */
    public void left(ChatSessions.Peer peer) { if (!sessions.holds(peer.key())) publish(peer, false); }

    /**
     * `POST /auth/sign-out` and every other revocation of a whole token family: her sockets are closed, which makes
     * {@link #left} publish the offline event through the ordinary close path. The dashboard closes its own socket on
     * logout; this is what happens when it cannot (a killed tab, a session revoked from another device).
     */
    @EventListener
    public void signedOut(SessionsRevoked revoked) {
        sessions.closeAll(ChatService.key(ChatService.USER, revoked.userId()), ChatSessions.SIGNED_OUT);
    }

    /**
     * From the bus, on every instance including the one that published: remember what changed and write the frame to
     * the peers whose sockets are held here. The thread lookup is skipped when this instance holds no socket at all,
     * which is the common case on an instance that is only serving REST.
     */
    void heard(ChatEvent e) {
        String key = e.senderKey();
        if (key == null || key.isBlank()) return;
        boolean online = Boolean.TRUE.equals(e.online());
        boolean known = online ? elsewhere.put(key, clock.instant().plus(ttl)) != null : elsewhere.remove(key) != null;
        if (online && known) return;                            // a refresh of what we already knew: nothing moved
        if (sessions.count() == 0) return;
        String frame = json.encodeShared(frame(key, online), ChatFrame.Companion.serializer());
        for (String peer : peersOf(key)) sessions.sendToSchool(peer, e.schoolId(), frame);
    }

    private void publish(ChatSessions.Peer peer, boolean online) {
        bus.publish(ChatEvent.presence(peer.schoolId(), peer.key(), online, clock.instant().toEpochMilli()));
    }

    /** `userId` for a dashboard user, `parentId` for a parent — the frame names her the way her own key does. */
    private static ChatFrame.Presence frame(String key, boolean online) {
        String id = key.substring(key.indexOf(':') + 1);
        boolean parent = key.startsWith(ChatService.PARENT + ":");
        return new ChatFrame.Presence(online, parent ? null : id, parent ? id : null);
    }

    /**
     * Whom her presence is anybody's business: the people she shares a thread with, and nobody else — presence is not
     * a staff register. A parent's peers are the staff on her children's threads; a staff member's are the other end
     * of each of hers, parent or colleague. Her own sessions are left out: they know.
     *
     * <p>Two statements for a staff key (her threads, then the parents of the children on them) and two per child for
     * a parent's, on a connect or a disconnect only — never per message.
     */
    private Set<String> peersOf(String key) {
        var out = new LinkedHashSet<String>();
        String id = key.substring(key.indexOf(':') + 1);
        if (key.startsWith(ChatService.PARENT + ":")) {
            for (var kid : children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(id))
                for (var t : threads.findByChildIdOrderByLastMessageAtDesc(kid.getId())) staffOn(t, out);
            return out;
        }
        var mine = threads.findForStaff(id);
        var childIds = mine.stream().map(Entities.ChatThreadEntity::getChildId).filter(Objects::nonNull).distinct().toList();
        var parents = new LinkedHashMap<String, String>();
        if (!childIds.isEmpty()) children.findAllById(childIds).forEach(c -> { if (c.getParentId() != null) parents.put(c.getId(), c.getParentId()); });
        for (var t : mine) {
            staffOn(t, out);
            String parentId = t.getChildId() == null ? null : parents.get(t.getChildId());
            if (parentId != null) out.add(ChatService.key(ChatService.PARENT, parentId));
        }
        out.remove(key);
        return out;
    }

    private static void staffOn(Entities.ChatThreadEntity t, Set<String> out) {
        out.add(ChatService.key(ChatService.USER, t.getTeacherId()));
        if (t.getPeerUserId() != null) out.add(ChatService.key(ChatService.USER, t.getPeerUserId()));
    }
}
