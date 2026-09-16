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
 *   <li>a parent: <strong>her child's school, read from the child in the path</strong>. A flagged parent route must
 *       therefore name one — `/children/{id}/**` — and a flagged parent route that does not, or that names a child
 *       which is not hers, is refused outright;</li>
 *   <li>nobody (a public route): the defaults.</li>
 * </ul>
 *
 * <p><strong>A parent fails closed</strong> (P4.0, from the #41 review). Until then a parent route with no child in
 * the path fell back to her <em>first</em> child's school, which is wrong twice over: a parent with children in two
 * schools had one school's flags decide what she saw about the other, and a parent with no children at all read the
 * platform defaults — so a feature no school had switched on was reachable. There is no fallback now. The flag is
 * resolved by the child in the path or the request is a 404, the same body an unknown route gets; a feature that
 * wants a parent-wide route has to say which child it is about.
 *
 * <p><strong>One flag decides a request.</strong> A handler's own {@link FeatureFlag} <em>replaces</em> its
 * controller's rather than adding to it: {@link #flagOf} looks at the method first and only falls back to the class
 * when the method carries none, so a route on a flagged class that names a second key is gated by the second key
 * alone — never by both. Put the narrower key on the handler when that is what you mean. Two gates on one route
 * would need both keys checked, and nothing asks for that yet; `FeatureFlagInterceptorTest` pins the replacement.
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
        var flag = flagOf(method);
        if (flag == null) return true;
        var scope = scopeOf(request);
        if (scope.resolved() && flags.isOn(scope.schoolId(), flag.value())) return true;
        throw hidden();
    }

    /** The method's own key when it has one, the controller's otherwise — a replacement, never a combination. */
    static FeatureFlag flagOf(HandlerMethod method) {
        var onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), FeatureFlag.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), FeatureFlag.class);
    }

    /**
     * Whose flags decide this request. {@code resolved} false means "there is nobody to ask" — a parent with no
     * child in the path, or one naming a child that is not hers — and the route is refused rather than answered
     * from someone else's school or from the platform defaults.
     */
    private Scope scopeOf(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof Principals.User) return new Scope(true, tenant.schoolId());
        if (principal instanceof Principals.Parent parent) return parentScope(parent, request);
        return new Scope(true, null);                                           // a public route reads the defaults
    }

    /** A parent's scope is the child in the path and nothing else; see the class doc on failing closed. */
    private Scope parentScope(Principals.Parent parent, HttpServletRequest request) {
        String childId = childIdIn(request);
        if (childId == null) return Scope.NONE;
        return children.findOneById(childId)
                .filter(c -> c.getDeletedAt() == null && c.getParentId().equals(parent.parentId()))
                .map(c -> new Scope(true, c.getSchoolId()))
                .orElse(Scope.NONE);
    }

    /** The same body `ApiExceptionHandler` gives an unknown path: which features a school has is not public. */
    private static ApiException hidden() { return new ApiException(HttpStatus.NOT_FOUND, "not_found", "No such endpoint."); }

    private record Scope(boolean resolved, String schoolId) {
        static final Scope NONE = new Scope(false, null);
    }

    /** `{id}` of a `/children/{id}/**` route, read from the mapping Spring already matched. */
    @SuppressWarnings("unchecked")
    private static String childIdIn(HttpServletRequest request) {
        if (!request.getRequestURI().startsWith("/children/")) return null;
        var variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables == null ? null : variables.get("id");
    }
}
