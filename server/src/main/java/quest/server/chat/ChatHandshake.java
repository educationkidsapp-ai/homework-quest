package quest.server.chat;

import java.util.Map;
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
 * verified by the same code the two request filters use, and is never logged. 401 for a token nobody issued, 403
 * for a role the chat has no side for (ADMIN, MANAGERIAL) and for a school with the `chat` flag off — a parent's
 * schools are her children's, and she is refused only when none of them has it.
 */
@Component
public class ChatHandshake implements HandshakeInterceptor {
    static final String PEER = "chat.peer";
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
            if (!"TEACHER".equals(user.role()) || user.schoolId() == null || !flags.isOn(user.schoolId(), FlagKeys.CHAT)) return refuse(response, HttpStatus.FORBIDDEN);
            attributes.put(PEER, new ChatSessions.Peer(ChatService.key(ChatService.TEACHER, user.userId()), ChatService.TEACHER, user.userId(), user.schoolId(), user));
            return true;
        }
        Principals.Parent parent = parents.verify(token).orElse(null);
        if (parent == null) return refuse(response, HttpStatus.UNAUTHORIZED);
        boolean anyOn = children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId()).stream().map(ChildEntity::getSchoolId).distinct()
                .anyMatch(school -> flags.isOn(school, FlagKeys.CHAT));
        if (!anyOn) return refuse(response, HttpStatus.FORBIDDEN);
        attributes.put(PEER, new ChatSessions.Peer(ChatService.key(ChatService.PARENT, parent.parentId()), ChatService.PARENT, parent.parentId(), null, parent));
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
