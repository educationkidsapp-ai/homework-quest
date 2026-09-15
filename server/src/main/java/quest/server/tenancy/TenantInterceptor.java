package quest.server.tenancy;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Resolves the request's school once, before the handler: an `X-School-Id` no school has is 404 `not_found` and a
 * TEACHER or MANAGERIAL pointing the header at another school is 403 `forbidden`, both as an `ApiException` so the
 * body is the usual `{code, message}`. Resolving here also means every transaction the handler opens finds the scope
 * memoised (see {@link TenantTransactionManager}).
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {
    private final TenantContext tenant;
    public TenantInterceptor(TenantContext tenant) { this.tenant = tenant; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        tenant.resolveEagerly();
        return true;
    }
}
