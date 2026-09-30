package quest.server.workers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quest.server.auth.AuditService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.platform.Phones;
import quest.server.tenancy.TenantContext;
import quest.server.workers.Entities.WorkerEntity;

/**
 * The Admin's Workers page (MA1, the owner's item 4): the school's non-teaching staff, created with a full name, a job
 * and a mobile number and nothing else.
 *
 * <p><strong>No account comes into being here.</strong> Unlike {@link quest.server.classes.TeachingStaffService} and
 * its coordinator and manager mirrors, this writes one row in one table: no `users` row, no password, no role, no
 * refresh token to revoke. The owner's page asks who works at the school, not who may sign in to it.
 *
 * <p><strong>Scope of the caller.</strong> Nothing here takes a school from the request: it is
 * {@link TenantContext#writeSchoolId()}, so an Admin who picked a school with `X-School-Id` acts in that one, and a
 * worker of another school is simply not found because the lookup is a filtered query.
 *
 * <p><strong>Retiring, not deleting.</strong> `DELETE /admin/workers/{id}` sets `active = false`. A person who has
 * left is part of the school's record and the page shows her greyed out with a way back.
 */
@Service
public class WorkerService {
    private static final int MAX_NAME = 80, MAX_JOB = 40;

    private final WorkerRepository workers; private final TenantContext tenant; private final AuditService audit;

    public WorkerService(WorkerRepository workers, TenantContext tenant, AuditService audit) {
        this.workers = workers; this.tenant = tenant; this.audit = audit;
    }

    /** Every worker of the school, retired ones included, in name order: one statement. */
    public List<WorkerDto.Worker> list() {
        return workers.findBySchoolIdOrderByFullNameAsc(tenant.writeSchoolId()).stream().map(WorkerService::dto).toList();
    }

    @Transactional
    public WorkerDto.Worker create(Principals.User caller, WorkerDto.CreateWorkerRequest request) {
        String schoolId = tenant.writeSchoolId();
        var row = new WorkerEntity();
        row.setId(UUID.randomUUID().toString()); row.setSchoolId(schoolId);
        row.setFullName(text(request.fullName(), "fullName", MAX_NAME)); row.setJob(text(request.job(), "job", MAX_JOB));
        row.setPhone(Phones.normalise(request.phone(), "phone")); row.setActive(true); row.setCreatedAt(Instant.now());
        workers.save(row);
        audit.record(caller.userId(), "worker.create", "worker", row.getId(), schoolId, Map.of("job", row.getJob()));
        return dto(row);
    }

    @Transactional
    public WorkerDto.Worker update(Principals.User caller, String id, WorkerDto.UpdateWorkerRequest request) {
        var row = worker(id);
        if (request.fullName() != null) row.setFullName(text(request.fullName(), "fullName", MAX_NAME));
        if (request.job() != null) row.setJob(text(request.job(), "job", MAX_JOB));
        if (request.phone() != null) row.setPhone(Phones.normalise(request.phone(), "phone"));
        if (request.active() != null) row.setActive(request.active());
        workers.save(row);
        audit.record(caller.userId(), "worker.update", "worker", row.getId(), row.getSchoolId(), Map.of("active", row.isActive()));
        return dto(row);
    }

    /** Retires her: the row, and whatever the school's record says about her, stays. */
    @Transactional
    public void retire(Principals.User caller, String id) {
        var row = worker(id);
        row.setActive(false);
        workers.save(row);
        audit.record(caller.userId(), "worker.retire", "worker", row.getId(), row.getSchoolId(), Map.of());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A worker of the caller's scope. The `school` filter is <em>not</em> what does it: `findById` is `em.find`, which
     * Hibernate filters never touch, so the explicit comparison below is the scope — the same reason
     * `ChildRepository.findOneById` exists as a query rather than a `findById`.
     */
    private WorkerEntity worker(String id) {
        return workers.findById(id).filter(w -> Objects.equals(tenant.writeSchoolId(), w.getSchoolId()))
                .orElseThrow(() -> ApiException.notFound("worker"));
    }

    private static WorkerDto.Worker dto(WorkerEntity row) {
        return new WorkerDto.Worker(row.getId(), row.getFullName(), row.getJob(), row.getPhone(), row.isActive(),
                row.getCreatedAt() == null ? 0L : row.getCreatedAt().toEpochMilli());
    }

    private static String text(String value, String field, int max) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty() || cleaned.length() > max) throw ApiException.badRequest(field + " is 1–" + max + " characters.");
        return cleaned;
    }
}
