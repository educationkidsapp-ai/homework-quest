package quest.server.workers;

import jakarta.validation.constraints.NotBlank;

/** The Java mirror of `quest.api.dashboard.Workers.kt` (MA1, the owner's item 4). */
public final class WorkerDto {
    private WorkerDto() {}

    /** One member of the school's non-teaching staff. No address and no role: there is no account behind her. */
    public record Worker(String id, String fullName, String job, String phone, boolean active, long createdAt) {}

    /** `POST /admin/workers`: the three fields the owner's page asks for, the mobile number optional. */
    public record CreateWorkerRequest(@NotBlank String fullName, @NotBlank String job, String phone) {}

    /** `PATCH /admin/workers/{id}`: only the fields that are present are written. */
    public record UpdateWorkerRequest(String fullName, String job, String phone, Boolean active) {}
}
