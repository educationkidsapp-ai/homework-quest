package quest.server.workers;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.tenancy.TeacherScope;

/**
 * `/admin/workers` (MA1, the owner's item 4): the school's non-teaching staff — the caretaker, the driver, the
 * nurse, the secretary — as a name, a job and a mobile number.
 *
 * <p>Feature 13 gave her the one thing she never had: an account, for the <strong>mobile app</strong> and nowhere
 * else, minted and reset by the two routes below ({@link WorkerAccessService}). The page itself is unchanged.
 *
 * <p><strong>`worker.read` is ADMIN and MANAGERIAL, `worker.write` and `worker.access` ADMIN only.</strong> The read costs one filtered
 * statement and the department manager's people directory (RM5) is the screen that wants it beside her teachers and
 * coordinators; who is on the school's payroll is the Admin's to change.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code TeacherAdminController} and its coordinator and manager
 * mirrors: this is one page of the Admin's People area, and a flag that could switch it off would leave her able to
 * create a teacher but not to record the caretaker — half a screen nobody could turn back on without a database
 * session.
 */
@RestController
@Tag(name = "Workers", description = "The school's non-teaching staff (no accounts)")
public class WorkerController {
    private final WorkerService workers; private final WorkerAccessService access;

    public WorkerController(WorkerService workers, WorkerAccessService access) { this.workers = workers; this.access = access; }

    @GetMapping(value = "/admin/workers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('worker.read')")
    public List<WorkerDto.Worker> workers() { return workers.list(); }

    @PostMapping(value = "/admin/workers", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('worker.write')")
    public WorkerDto.Worker createWorker(@AuthenticationPrincipal Principals.User caller,
                                        @RequestBody @Valid WorkerDto.CreateWorkerRequest body) {
        return workers.create(TeacherScope.require(caller), body);
    }

    @PatchMapping(value = "/admin/workers/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('worker.write')")
    public WorkerDto.Worker updateWorker(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                        @RequestBody @Valid WorkerDto.UpdateWorkerRequest body) {
        return workers.update(TeacherScope.require(caller), id, body);
    }

    /**
     * Feature 13: her mobile-app account — the one thing a worker never had. ADMIN only (`worker.access`, not
     * `worker.write`): recording who sweeps the yard and handing that person a login are two different decisions,
     * and only the second mints a password. 409 when she already has one; the username and the temporary password
     * are answered <strong>once</strong>, and nothing reads them back.
     */
    @PostMapping(value = "/admin/workers/{id}/app-access", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('worker.access')")
    public WorkerDto.WorkerAccess enableWorkerAppAccess(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return access.enable(TeacherScope.require(caller), id);
    }

    /** A new one-time password on that account, the Teachers page's own reset for a worker (`StaffAccounts.reset`). */
    @PostMapping(value = "/admin/workers/{id}/reset-password", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('worker.access')")
    public WorkerDto.WorkerAccess resetWorkerPassword(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return access.resetPassword(TeacherScope.require(caller), id);
    }

    /** Retires her rather than deleting the row: a person who has left is part of the school's record. */
    @DeleteMapping("/admin/workers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('worker.write')")
    public void retireWorker(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        workers.retire(TeacherScope.require(caller), id);
    }
}
