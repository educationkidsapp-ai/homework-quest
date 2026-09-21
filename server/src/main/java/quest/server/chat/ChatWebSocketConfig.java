package quest.server.chat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * `/ws/chat` on the API itself (D24): same origin as the dashboard, same tokens as REST, no second service. Plain
 * Spring WebSocket — no STOMP, no SockJS. Allowed origins are the CORS list, so a local Angular dev server can
 * connect; the app sends no `Origin` and is allowed as such. The 8 KB frame limit and the idle rule are per session
 * ({@link ChatSocketHandler}, {@link ChatSessions}) rather than a container bean, which the MockMvc test contexts
 * could not create.
 */
@Configuration
@EnableWebSocket
public class ChatWebSocketConfig implements WebSocketConfigurer {
    private final ChatSocketHandler handler; private final ChatHandshake handshake; private final String origins;

    public ChatWebSocketConfig(ChatSocketHandler handler, ChatHandshake handshake, @Value("${quest.cors-origins:http://localhost:8081,http://localhost:8080}") String origins) {
        this.handler = handler; this.handshake = handshake; this.origins = origins;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/chat").addInterceptors(handshake).setAllowedOriginPatterns(origins.split(","));
    }
}
