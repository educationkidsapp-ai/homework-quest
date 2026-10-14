package quest.server.staffattendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 18's requests. Transactional for the reason every repository of a tenant entity is
 * (`TenantArchitectureTest`); every query also names the school.
 */
@Transactional(readOnly = true)
public interface StaffAttendanceRequestRepository extends JpaRepository<Entities.StaffAttendanceRequestEntity, String> {
    Optional<Entities.StaffAttendanceRequestEntity> findOneBySchoolIdAndId(String schoolId, String id);

    List<Entities.StaffAttendanceRequestEntity> findBySchoolIdAndStaffKindAndStaffIdOrderByCreatedAtDesc(
            String schoolId, String staffKind, String staffId);

    List<Entities.StaffAttendanceRequestEntity> findBySchoolIdOrderByCreatedAtDesc(String schoolId);

    List<Entities.StaffAttendanceRequestEntity> findBySchoolIdAndStatusOrderByCreatedAtDesc(String schoolId, String status);

    /** Whether the employee holds an approved remote day for {@code day} — what lets her check in from home. */
    boolean existsBySchoolIdAndStaffKindAndStaffIdAndDayAndTypeAndStatus(String schoolId, String staffKind, String staffId,
                                                                         LocalDate day, String type, String status);

    /** The attachment's request, for `MediaAccess`: who may read a correction's photo is who may read the request. */
    Optional<Entities.StaffAttendanceRequestEntity> findFirstBySchoolIdAndAttachmentId(String schoolId, String attachmentId);

    /** Feature 17's report and the employee day: how many corrections were applied to her in a range. */
    @Query("select count(r) from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.staffKind = ?2 and r.staffId = ?3"
            + " and r.type = 'CORRECTION' and r.status = 'APPROVED' and r.day between ?4 and ?5")
    long countAppliedCorrections(String schoolId, String staffKind, String staffId, LocalDate from, LocalDate to);
}
