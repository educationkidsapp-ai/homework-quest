package quest.server.tenancy;

import org.springframework.stereotype.Component;
import quest.server.config.ApiException;

/**
 * Which school the current request runs against. The JWT filter fills it in: a TEACHER or MANAGERIAL token carries its
 * own `schoolId`, an ADMIN token carries none and scopes with the `X-School-Id` header instead (D6: an Admin without
 * the header reads across schools and writes into the default school, which keeps `webAdmin/` working).
 * Only the holder lives here — the Hibernate filter that enforces it is P1.2.
 */
@Component
public class TenantContext {
    /** The default school every pre-tenancy row was migrated into. */
    public static final String DEFAULT_SCHOOL = "default";
    public static final String HEADER = "X-School-Id";

    private record Scope(String role, String tokenSchoolId, String headerSchoolId) {}
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private final SchoolRepository schools;
    public TenantContext(SchoolRepository schools) { this.schools = schools; }

    public void set(String role, String tokenSchoolId, String headerSchoolId) { CURRENT.set(new Scope(role, blankToNull(tokenSchoolId), blankToNull(headerSchoolId))); }
    public void clear() { CURRENT.remove(); }

    /** The caller's role, or null outside an authenticated dashboard request. */
    public String role() { var s = CURRENT.get(); return s == null ? null : s.role(); }

    /** The school to read: the token's school, or the Admin's `X-School-Id` header, or null (Admin sees everything). */
    public String schoolId() {
        var s = CURRENT.get();
        if (s == null) return null;
        if (s.tokenSchoolId() != null) return s.tokenSchoolId();
        if ("ADMIN".equals(s.role()) && s.headerSchoolId() != null) {
            if (!schools.existsById(s.headerSchoolId())) throw ApiException.notFound("school");
            return s.headerSchoolId();
        }
        return null;
    }

    /** The school to write into: `schoolId()`, or the default school for an Admin who did not pick one. */
    public String writeSchoolId() { var id = schoolId(); return id == null ? DEFAULT_SCHOOL : id; }

    private static String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
}
