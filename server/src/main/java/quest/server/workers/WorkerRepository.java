package quest.server.workers;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is: Spring Data makes only the `CrudRepository`
 * methods transactional, and a derived query outside a transaction never reaches
 * {@link quest.server.tenancy.TenantTransactionManager}, where the `school` filter is switched on.
 * `TenantArchitectureTest` fails if this annotation is lost.
 */
@Transactional(readOnly = true)
public interface WorkerRepository extends JpaRepository<Entities.WorkerEntity, String> {
    List<Entities.WorkerEntity> findBySchoolIdOrderByFullNameAsc(String schoolId);
    long countBySchoolIdAndActiveTrue(String schoolId);

    /**
     * Feature 15: the worker behind a WORKER token. Her principal carries `users.id` — the account feature 13 minted
     * for her — and everything about attendance is keyed by `workers.id`, so this is the one hop between the two.
     * The school is named as well as the account, because `users.id` is unique but the caller's school is what
     * decides whether the row is hers to read.
     */
    Optional<Entities.WorkerEntity> findOneBySchoolIdAndUserId(String schoolId, String userId);
}
