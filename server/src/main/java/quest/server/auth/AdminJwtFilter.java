package quest.server.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import quest.server.tenancy.TenantContext;

/** Authenticates a dashboard token, grants `ROLE_<role>` and opens the tenant scope for the request. */
@Component
public class AdminJwtFilter extends OncePerRequestFilter {
    /** An impersonated session carries the target's role plus this authority; {@link ReadOnlyGuard} enforces it. */
    public static final String READ_ONLY = "READ_ONLY";

    private final AdminJwtService jwt; private final TenantContext tenant;
    public AdminJwtFilter(AdminJwtService jwt, TenantContext tenant) { this.jwt = jwt; this.tenant = tenant; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer admin.")) {
            jwt.verify(header.substring(7).trim()).ifPresent(user -> {
                List<GrantedAuthority> authorities = new ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                if (user.isImpersonated()) authorities.add(new SimpleGrantedAuthority(READ_ONLY));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
                tenant.set(user.role(), user.schoolId(), request.getHeader(TenantContext.HEADER));
            });
        }
        try { chain.doFilter(request, response); } finally { tenant.clear(); }
    }
}
