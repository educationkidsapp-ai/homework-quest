package quest.server.staffattendance;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The attempt log. Written on every call, read by nobody over HTTP yet: feature 16's alerts and the Admin's
 * suspicious-attempt view are what it is for. Transactional and school-named for the reason in
 * {@link StaffCheckInRepository}.
 */
@Transactional(readOnly = true)
public interface StaffCheckInAttemptRepository extends JpaRepository<Entities.StaffCheckInAttemptEntity, String> {
    List<Entities.StaffCheckInAttemptEntity> findBySchoolIdAndStaffKindAndStaffIdOrderByServerAtAsc(
            String schoolId, String staffKind, String staffId);
}
