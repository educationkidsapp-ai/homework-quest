package quest.server.flags;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import quest.server.auth.Principals;
import quest.server.children.ChildRepository;
import quest.server.config.ApiException;
import quest.server.tenancy.TenantContext;

/**
 * §4: "the flag is checked in the server (endpoint returns 404 when off)". Every handler carrying
 * {@link FeatureFlag} — on the method or on its controller class — is refused with the same body an unknown route
 * gets, so a school cannot tell a switched-off feature from one that was never built.
 *
 * <p>Whose flags apply:
 * <ul>
 *   <li>a dashboard user: the school her token carries, or the one an ADMIN picked with `X-School-Id`. An ADMIN
 *       who picked none has no school, and reads the flags' defaults (D6);</li>
 *   <li>a parent: her child's school. On `/children/{id}/**` that is the child in the path; on every other parent
 *       route (there is no child to read it from) it is <strong>her first child's school</strong> — the one she
 *       added first, which is the school the app shows her. A parent with no children yet reads the defaults;</li>
 *   <li>nobody (a public route): the defaults.</li>
 * </ul>
 *
 * <p>One flag decides a request: a handler's own {@link FeatureFlag} <em>replaces</em> its controller's rather than
 * adding to it, so a route on a flagged class that names a second key is gated by the second key alone. Put the
 * narrower key on the handler when that is what you mean; two gates on one route would need both keys checked, and
 * nothing asks for that yet.
 */
@Component
public class FeatureFlagInterceptor implements HandlerInterceptor {
    private final FeatureFlags flags; private final TenantContext tenant; private final ChildRepository children;

    public FeatureFlagInterceptor(FeatureFlags flags, TenantContext tenant, ChildRepository children) {
        this.flags = flags; this.tenant = tenant; this.children = children;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        var flag = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), FeatureFlag.class);
        if (flag == null) flag = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), FeatureFlag.class);
        if (flag == null) return true;
        if (flags.isOn(schoolId(request), flag.value())) return true;
        // The same body `ApiExceptionHandler` gives an unknown path: which features a school has is not public.
        throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint.");
    }

    private String schoolId(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof Principals.User) return tenant.schoolId();
        if (principal instanceof Principals.Parent parent) return parentSchoolId(parent, request);
        return null;
    }

    private String parentSchoolId(Principals.Parent parent, HttpServletRequest request) {
        String childId = childIdIn(request);
        if (childId != null) {
            var child = children.findOneById(childId)
                    .filter(c -> c.getDeletedAt() == null && c.getParentId().equals(parent.parentId()));
            if (child.isPresent()) return child.get().getSchoolId();
        }
        var own = children.findByParentIdAndDeletedAtIsNullOrderByCreatedAt(parent.parentId());
        return own.isEmpty() ? null : own.get(0).getSchoolId();
    }

    /** `{id}` of a `/children/{id}/**` route, read from the mapping Spring already matched. */
    @SuppressWarnings("unchecked")
    private static String childIdIn(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/children/")) return null;
        var variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables == null ? null : variables.get("id");
    }
}
