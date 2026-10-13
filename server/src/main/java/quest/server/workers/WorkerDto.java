package quest.server.workers;

import jakarta.validation.constraints.NotBlank;

/** The Java mirror of `quest.api.dashboard.Workers.kt` (MA1, the owner's item 4). */
public final class WorkerDto {
    private WorkerDto() {}

    /**
     * One member of the school's non-teaching staff. Feature 13 gave her the one thing she never had: a login, for
     * the mobile app only. `username` is what she types (`users.email`, the login column), absent while she has no
     * account; the password is never on this row, because it is answered once by the route that sets it.
     */
    public record Worker(String id, String fullName, String job, String phone, boolean active, long createdAt,
                         boolean hasAppAccess, String username) {}

    /**
     * Feature 13: the body of `POST /admin/workers/{id}/app-access` and of `POST /admin/workers/{id}/reset-password`
     * — what the Admin reads out to her, once. Neither value is readable afterwards.
     */
    public record WorkerAccess(String username, String temporaryPassword) {}

    /** `POST /admin/workers`: the three fields the owner's page asks for, the mobile number optional. */
    public record CreateWorkerRequest(@NotBlank String fullName, @NotBlank String job, String phone) {}

    /** `PATCH /admin/workers/{id}`: only the fields that are present are written. */
    public record UpdateWorkerRequest(String fullName, String job, String phone, Boolean active) {}
}
