package quest.server.tenancy;

import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * Which school the current request runs against. The JWT filter fills it in: a TEACHER or MANAGERIAL token carries its
 * own `schoolId`, an ADMIN token carries none and scopes with the `X-School-Id` header instead (D6: an Admin without
 * the header reads across schools and writes into the default school, which keeps `webAdmin/` working).
 *
 * <p>The scope is resolved once per request — eagerly by {@link TenantInterceptor}, lazily otherwise — and memoised,
 * because {@link TenantTransactionManager} asks for it at the start of every transaction and the validating lookup in
 * `schools` is itself a query. While that lookup runs the scope reports "not scoped yet" so the nested transaction
 * does not recurse; `schools` is not a tenant table, so it needs no filter.
 *
 * <p>The filter that enforces the scope is {@link TenantFilter}.
 */
@Component
public class TenantContext {
    /** The default school every pre-tenancy row was migrated into. */
    public static final String DEFAULT_SCHOOL = "default";
    public static final String HEADER = "X-School-Id";

    private static final class Scope {
        private final String role; private final String tokenSchoolId; private final String headerSchoolId;
        private boolean resolved; private boolean resolving; private String schoolId;
        Scope(String role, String tokenSchoolId, String headerSchoolId) { this.role = role; this.tokenSchoolId = tokenSchoolId; this.headerSchoolId = headerSchoolId; }
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private final SchoolRepository schools;
    public TenantContext(SchoolRepository schools) { this.schools = schools; }

    public void set(String role, String tokenSchoolId, String headerSchoolId) { CURRENT.set(new Scope(role, blankToNull(tokenSchoolId), blankToNull(headerSchoolId))); }
    public void clear() { CURRENT.remove(); }

    /** The caller's role, or null outside an authenticated dashboard request. */
    public String role() { var s = CURRENT.get(); return s == null ? null : s.role; }

    /** True for a dashboard user who is not the platform ADMIN. */
    public boolean isAdmin() { return "ADMIN".equals(role()); }

    /** The `X-School-Id` the caller sent, if any. */
    public String headerSchoolId() { var s = CURRENT.get(); return s == null ? null : s.headerSchoolId; }

    /** The school to read: the token's school, or the Admin's `X-School-Id` header, or null (Admin sees everything). */
    public String schoolId() {
        var s = CURRENT.get();
        if (s == null) return null;
        if (s.resolved) return s.schoolId;
        if (s.resolving) return null;                                           // the `schools` lookup below runs unscoped
        s.resolving = true;
        try {
            String id = null;
            if (s.tokenSchoolId != null) id = s.tokenSchoolId;
            else if ("ADMIN".equals(s.role) && s.headerSchoolId != null) {
                if (!schools.existsById(s.headerSchoolId)) throw ApiException.notFound("school");
                id = s.headerSchoolId;
            }
            s.schoolId = id; s.resolved = true;
            return id;
        } finally { s.resolving = false; }
    }

    /**
     * Validates the request's scope once, before any handler runs: 404 for an `X-School-Id` no school has, 403 when a
     * TEACHER or MANAGERIAL points the header at a school that is not hers. Called by {@link TenantInterceptor}.
     */
    public void resolveEagerly() {
        var s = CURRENT.get();
        if (s == null) return;
        if (s.headerSchoolId != null && !"ADMIN".equals(s.role) && !s.headerSchoolId.equals(s.tokenSchoolId))
            throw ApiException.forbidden("This account belongs to another school.");
        schoolId();
    }

    /** The school to write into: `schoolId()`, or the default school for an Admin who did not pick one. */
    public String writeSchoolId() { var id = schoolId(); return id == null ? DEFAULT_SCHOOL : id; }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
