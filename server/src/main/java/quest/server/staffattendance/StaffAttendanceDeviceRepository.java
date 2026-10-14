package quest.server.staffattendance;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The phones bound to an employee. The live binding is the row with no `reset_at`, which is what
 * {@code findOneBySchoolIdAndStaffKindAndStaffIdAndResetAtIsNull} asks for; the reset rows stay behind it as the
 * history of which phones she used. Transactional and school-named for the reason in {@link StaffCheckInRepository}.
 */
@Transactional(readOnly = true)
public interface StaffAttendanceDeviceRepository extends JpaRepository<Entities.StaffAttendanceDeviceEntity, String> {
    Optional<Entities.StaffAttendanceDeviceEntity> findOneBySchoolIdAndStaffKindAndStaffIdAndResetAtIsNull(
            String schoolId, String staffKind, String staffId);

    List<Entities.StaffAttendanceDeviceEntity> findBySchoolIdAndStaffKindAndStaffIdOrderByFirstSeenAtAsc(
            String schoolId, String staffKind, String staffId);
}
