package quest.server.auth;

import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The permission evaluator behind every `@PreAuthorize("@permit.has('lesson.publish')")`: the key is looked up in
 * `permissions.json` and checked against the caller's role, so that file is the only place a permission is defined
 * (§5). Anonymous callers count as `PUBLIC`, a Firebase parent as `PARENT`.
 *
 * <p>It is a bean reference rather than a one-argument `hasPermission(…)` because Spring Security builds its
 * expression root in a private method (`DefaultMethodSecurityExpressionHandler#createSecurityExpressionRoot(Supplier,
 * MethodInvocation)`), so the root cannot be extended without reimplementing the evaluation context. The bean
 * resolver, on the other hand, is a supported extension point.
 */
@Component("permit")
public class PermissionExpressions {
    private final Permissions permissions;
    public PermissionExpressions(Permissions permissions) { this.permissions = permissions; }

    /** Does the caller's role hold this key? Unknown keys are refused, which is what `PreAuthorizeCoverageTest` guards. */
    public boolean has(String permission) {
        if (permissions.roles(permission).contains(Permissions.PUBLIC)) return true;
        return permissions.grants(callerRole(SecurityContextHolder.getContext().getAuthentication()), permission);
    }

    /** `ADMIN` | `TEACHER` | `MANAGERIAL` | `PARENT`, or `PUBLIC` when nobody is signed in. */
    static String callerRole(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return Permissions.PUBLIC;
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority().toUpperCase(Locale.ROOT))
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .findFirst().orElse(Permissions.PUBLIC);
    }
}
