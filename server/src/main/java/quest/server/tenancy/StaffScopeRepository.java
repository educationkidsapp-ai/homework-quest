package quest.server.tenancy;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * `staff_scopes` (R2, DR1). Transactional for the reason every other tenant repository is: Spring Data only wraps the
 * `CrudRepository` methods and the `school` filter is enabled per transaction ({@link TenantTransactionManager}), so
 * a derived query outside one would run unfiltered. `TenantArchitectureTest` fails if the annotation is lost.
 *
 * <p>Every query names the school, so a scope row of another school is invisible twice over — by the filter and by
 * the parameter. The user id is always the caller's own or one an Admin resolved from `users`, never a UI parameter.
 */
@Transactional(readOnly = true)
public interface StaffScopeRepository extends JpaRepository<Entities.StaffScopeEntity, String> {
    List<Entities.StaffScopeEntity> findBySchoolIdAndUserIdOrderBySubjectAscCurriculumAsc(String schoolId, String userId);
    List<Entities.StaffScopeEntity> findBySchoolIdAndUserIdInOrderBySubjectAscCurriculumAsc(String schoolId, List<String> userIds);
}
