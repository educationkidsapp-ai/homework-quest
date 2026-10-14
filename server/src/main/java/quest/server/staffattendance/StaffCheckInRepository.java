package quest.server.staffattendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 15's attendance records. Transactional for the reason every repository of a tenant entity is: a derived
 * query outside a transaction never reaches {@link quest.server.tenancy.TenantTransactionManager}, where the `school`
 * filter is switched on (`TenantArchitectureTest`). Every query also names the school, so the filter is belt and
 * braces rather than the only thing between two schools.
 */
@Transactional(readOnly = true)
public interface StaffCheckInRepository extends JpaRepository<Entities.StaffCheckInEntity, String> {
    Optional<Entities.StaffCheckInEntity> findOneBySchoolIdAndStaffKindAndStaffIdAndDay(
            String schoolId, String staffKind, String staffId, LocalDate day);

    List<Entities.StaffCheckInEntity> findBySchoolIdAndStaffKindAndStaffIdAndDayBetweenOrderByDayAsc(
            String schoolId, String staffKind, String staffId, LocalDate from, LocalDate to);
}
