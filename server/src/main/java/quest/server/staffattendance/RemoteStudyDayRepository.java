package quest.server.staffattendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 23's remote study days (V46), which replaced feature 18's `attendance_remote_days`. Transactional for the
 * reason every repository of a tenant entity is (`TenantArchitectureTest`); every query also names the school,
 * because the attendance job has no request to take one from.
 */
@Transactional(readOnly = true)
public interface RemoteStudyDayRepository extends JpaRepository<Entities.RemoteStudyDayEntity, String> {
    List<Entities.RemoteStudyDayEntity> findBySchoolIdAndDay(String schoolId, LocalDate day);

    List<Entities.RemoteStudyDayEntity> findBySchoolIdAndDayGreaterThanEqualOrderByDayAsc(String schoolId, LocalDate from);

    List<Entities.RemoteStudyDayEntity> findBySchoolIdAndDayBetweenOrderByDayAsc(String schoolId, LocalDate from, LocalDate to);

    Optional<Entities.RemoteStudyDayEntity> findOneBySchoolIdAndId(String schoolId, String id);
}
