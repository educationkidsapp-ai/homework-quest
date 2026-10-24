package quest.server.staffattendance;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 34 (V57): the yearly allowance of one leave type for one staff category. <strong>No row means no
 * balance</strong>, so nothing is refused for being over budget. Transactional for the reason every repository of a
 * tenant entity is (`TenantArchitectureTest`); every query also names the school.
 */
@Transactional(readOnly = true)
public interface LeaveBalanceRepository extends JpaRepository<Entities.LeaveBalanceEntity, String> {
    List<Entities.LeaveBalanceEntity> findBySchoolIdOrderByLeaveTypeAscCategoryAsc(String schoolId);

    Optional<Entities.LeaveBalanceEntity> findOneBySchoolIdAndLeaveTypeAndCategory(String schoolId, String leaveType, String category);
}
