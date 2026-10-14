package quest.server.staffattendance;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is: a derived query outside a transaction never
 * reaches {@link quest.server.tenancy.TenantTransactionManager}, where the `school` filter is switched on
 * (`TenantArchitectureTest`). Every query also names the school, so a job with no request scope reads one school.
 */
@Transactional(readOnly = true)
public interface WorkScheduleRepository extends JpaRepository<Entities.WorkScheduleEntity, String> {
    List<Entities.WorkScheduleEntity> findBySchoolId(String schoolId);
    Optional<Entities.WorkScheduleEntity> findOneBySchoolIdAndCategory(String schoolId, String category);
}
