package quest.server.staffattendance;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 34 (V57): one row per (school, leave type) — whether the school offers it and its document rule. No row is
 * the default, "offered with no document rule". Transactional for the reason every repository of a tenant entity is
 * (`TenantArchitectureTest`); every query also names the school.
 */
@Transactional(readOnly = true)
public interface LeaveTypeRepository extends JpaRepository<Entities.LeaveTypeEntity, String> {
    List<Entities.LeaveTypeEntity> findBySchoolIdOrderByLeaveTypeAsc(String schoolId);

    Optional<Entities.LeaveTypeEntity> findOneBySchoolIdAndLeaveType(String schoolId, String leaveType);
}
