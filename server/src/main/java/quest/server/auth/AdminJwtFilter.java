package quest.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import quest.server.tenancy.TenantContext;

/** Authenticates a dashboard token, grants `ROLE_<role>` and opens the tenant scope for the request. */
@Component
public class AdminJwtFilter extends OncePerRequestFilter {
    private final AdminJwtService jwt; private final TenantContext tenant;
    public AdminJwtFilter(AdminJwtService jwt, TenantContext tenant) { this.jwt = jwt; this.tenant = tenant; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer admin.")) {
            jwt.verify(header.substring(7).trim()).ifPresent(user -> {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
                tenant.set(user.role(), user.schoolId(), request.getHeader(TenantContext.HEADER));
            });
        }
        try { chain.doFilter(request, response); } finally { tenant.clear(); }
    }
}
