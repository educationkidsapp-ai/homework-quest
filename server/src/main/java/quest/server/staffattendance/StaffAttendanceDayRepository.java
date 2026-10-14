package quest.server.staffattendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`); every query also
 * names the school, because the attendance job has no request and therefore no filter.
 */
@Transactional(readOnly = true)
public interface StaffAttendanceDayRepository extends JpaRepository<Entities.StaffAttendanceDayEntity, String> {
    Optional<Entities.StaffAttendanceDayEntity> findOneBySchoolIdAndStaffKindAndStaffIdAndDay(String schoolId, String staffKind,
                                                                                              String staffId, LocalDate day);
    List<Entities.StaffAttendanceDayEntity> findBySchoolIdAndDay(String schoolId, LocalDate day);
}
