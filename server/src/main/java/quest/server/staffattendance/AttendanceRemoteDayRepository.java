package quest.server.staffattendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 18's remote days. Transactional for the reason every repository of a tenant entity is
 * (`TenantArchitectureTest`); every query also names the school, because the attendance job has no request.
 */
@Transactional(readOnly = true)
public interface AttendanceRemoteDayRepository extends JpaRepository<Entities.AttendanceRemoteDayEntity, String> {
    List<Entities.AttendanceRemoteDayEntity> findBySchoolIdAndDay(String schoolId, LocalDate day);

    List<Entities.AttendanceRemoteDayEntity> findBySchoolIdAndDayGreaterThanEqualOrderByDayAsc(String schoolId, LocalDate from);

    Optional<Entities.AttendanceRemoteDayEntity> findOneBySchoolIdAndId(String schoolId, String id);
}
