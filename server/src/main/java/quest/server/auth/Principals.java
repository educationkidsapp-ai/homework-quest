package quest.server.auth;

/** What a request runs as, after the filters. */
public final class Principals {
    private Principals() {}
    public record Parent(String parentId, String uid, String email) {}
    /** A dashboard user: ADMIN (schoolId null, scopes with `X-School-Id`), TEACHER or MANAGERIAL (one school). */
    public record User(String userId, String email, String role, String schoolId, String impersonatedBy) {
        public User(String userId, String email, String role, String schoolId) { this(userId, email, role, schoolId, null); }
        public boolean isAdmin() { return "ADMIN".equals(role); }
        /** True while an Admin is viewing the dashboard as this user (§5); the request is read-only and audit-logged. */
        public boolean isImpersonated() { return impersonatedBy != null; }
    }
}
