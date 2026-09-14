package quest.server.auth;

/** What a request runs as, after the filters. */
public final class Principals {
    private Principals() {}
    public record Parent(String parentId, String uid, String email) {}
    public record Admin(String adminId, String email) {}
}
