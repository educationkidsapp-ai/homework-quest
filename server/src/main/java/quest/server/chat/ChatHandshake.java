package quest.server.chat;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import quest.server.auth.AdminJwtService;
import quest.server.auth.FirebaseTokenFilter;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.children.Entities.ChildEntity;
import quest.server.flags.FeatureFlags;
import quest.server.flags.FlagKeys;

/**
 * Who is opening `/ws/chat`. The token is `Authorization: Bearer …` when the client can send headers (the app) and
 * `?token=` when it cannot (a browser `WebSocket`); either kind — a dashboard JWT or a Firebase ID token — is
 * verified by the same code the two request filters use, and is never logged. 401 for a token nobody issued.
 *
 * <p>D26 made this socket the dashboard's event channel rather than only its chat: ADMIN, MANAGERIAL and TEACHER
 * are all admitted, with the `chat` flag on or off, because notifications ride the same connection. What the flag
 * still decides is whether the peer may <em>send a chat command</em> on it ({@link ChatSessions.Peer#chat}) — a
 * teacher of a flag-off school gets her lesson notifications and an `error` frame if she tries to write, exactly
 * as the REST half 404s for her. A dashboard principal with no school at all is refused, as it is on every route.
 * Parents are unchanged: a parent's schools are her children's, and she is refused only when none has the flag on.
 */
@Component
public class ChatHandshake implements HandshakeInterceptor {
    static final String PEER = "chat.peer";
    /** The three roles a dashboard JWT can carry; every one of them may hold a socket (D26). */
    private static final Set<String> DASHBOARD_ROLES = Set.of("ADMIN", "TEACHER", "MANAGERIAL");
    private final AdminJwtService jwt; private final FirebaseTokenFilter parents; private final ChildRepository children; private final FeatureFlags flags;

    public ChatHandshake(AdminJwtService jwt, FirebaseTokenFilter parents, ChildRepository children, FeatureFlags flags) {
        this.jwt = jwt; this.parents = parents; this.children = children; this.flags = flags;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        String token = token(request);
        if (token == null) return refuse(response, HttpStatus.UNAUTHORIZED);
        if (token.startsWith("admin.")) {
            var user = jwt.verify(token).orElse(null);
            if (user == null) return refuse(response, HttpStatus.UNAUTHORIZED);
            if (!DASHBOARD_ROLES.contains(user.role())) return refuse(response, HttpStatus.FORBIDDEN);
            if (!"ADMIN".equals(user.role()) && user.schoolId() == null) return refuse(response, HttpStatus.FORBIDDEN);
            boolean chat = "TEACHER".equals(user.role()) && flags.isOn(user.schoolId(), FlagKeys.CHAT);
            attributes.put(PEER, new ChatSessions.Peer(ChatService.key(ChatService.USER, user.userId()), user.role().toLowerCase(Locale.ROOT),
                    user.userId(), user.schoolId(), user, chat));
            return true;
        }
        Principals.Parent parent = parents.verify(token).orElse(null);
        if (parent == null) return refuse(response, HttpStatus.UNAUTHORIZED);
        boolean anyOn = children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId()).stream().map(ChildEntity::getSchoolId).distinct()
                .anyMatch(school -> flags.isOn(school, FlagKeys.CHAT));
        if (!anyOn) return refuse(response, HttpStatus.FORBIDDEN);
        attributes.put(PEER, new ChatSessions.Peer(ChatService.key(ChatService.PARENT, parent.parentId()), ChatService.PARENT, parent.parentId(), null, parent, true));
        return true;
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception exception) {}

    private static String token(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith("Bearer ")) return header.substring(7).trim();
        if (request instanceof ServletServerHttpRequest servlet) { String q = servlet.getServletRequest().getParameter("token"); if (q != null && !q.isBlank()) return q.trim(); }
        return null;
    }

    private static boolean refuse(ServerHttpResponse response, HttpStatus status) { response.setStatusCode(status); return false; }
}
