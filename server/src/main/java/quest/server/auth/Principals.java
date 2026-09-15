package quest.server.auth;

/** What a request runs as, after the filters. */
public final class Principals {
    private Principals() {}
    public record Parent(String parentId, String uid, String email) {}
    /** A dashboard user: ADMIN (schoolId null, scopes with `X-School-Id`), TEACHER or MANAGERIAL (one school). */
    public record User(String userId, String email, String role, String schoolId) {
        public boolean isAdmin() { return "ADMIN".equals(role); }
    }
}
