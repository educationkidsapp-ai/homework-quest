package quest.server.workers;

import java.util.List;
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
}
