package quest.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import quest.server.config.Json;

/**
 * §5 "View as…": an impersonated session may look, never touch. Every non-GET request is refused with 403 and every
 * request is audit-logged — at most one row per (actor, target, path) per minute, so a dashboard that polls does not
 * fill the table.
 */
@Component
public class ReadOnlyGuard extends OncePerRequestFilter {
    private static final Duration AUDIT_EVERY = Duration.ofMinutes(1);

    private final AuditService audit; private final Json json;
    private final Map<String, Instant> lastAudited = new ConcurrentHashMap<>();
    public ReadOnlyGuard(AuditService audit, Json json) { this.audit = audit; this.json = json; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean readOnly = auth != null && auth.getAuthorities().stream().anyMatch(a -> AdminJwtFilter.READ_ONLY.equals(a.getAuthority()));
        if (!readOnly) { chain.doFilter(request, response); return; }

        var user = (Principals.User) auth.getPrincipal();
        audit(user, request);
        if (!isRead(request.getMethod())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(json.write(new quest.server.config.ApiException.ApiError("forbidden", "You are viewing as someone else; this view is read-only.")));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isRead(String method) { return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method); }

    private void audit(Principals.User user, HttpServletRequest request) {
        String key = user.impersonatedBy() + "|" + user.userId() + "|" + request.getRequestURI();
        Instant now = Instant.now();
        Instant previous = lastAudited.get(key);
        if (previous != null && previous.isAfter(now.minus(AUDIT_EVERY))) return;
        if (lastAudited.size() > 1_000) lastAudited.values().removeIf(seen -> seen.isBefore(now.minus(AUDIT_EVERY)));
        lastAudited.put(key, now);
        audit.record(user.impersonatedBy(), "user.impersonate.request", "user", user.userId(), user.schoolId(),
                Map.of("method", request.getMethod(), "path", request.getRequestURI()));
    }
}
