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

    /**
     * Feature 34: every approved leave or permission of one employee that touches the range, which is what
     * {@link Excuses} reads. A leave runs from `day` to `to_day` (null means `day` alone) and a permission sits on
     * `day`, so one overlap test covers both.
     */
    @Query("select r from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.staffKind = ?2 and r.staffId = ?3"
            + " and r.status = 'APPROVED' and r.type in ('LEAVE', 'PERMISSION')"
            + " and r.day <= ?5 and coalesce(r.toDay, r.day) >= ?4 order by r.day asc")
    List<Entities.StaffAttendanceRequestEntity> approvedExcuses(String schoolId, String staffKind, String staffId,
                                                                LocalDate from, LocalDate to);

    /** Feature 34: the same for every employee of one school at once — the board and the report read a whole month. */
    @Query("select r from StaffAttendanceRequestEntity r where r.schoolId = ?1"
            + " and r.status = 'APPROVED' and r.type in ('LEAVE', 'PERMISSION')"
            + " and r.day <= ?3 and coalesce(r.toDay, r.day) >= ?2 order by r.day asc")
    List<Entities.StaffAttendanceRequestEntity> approvedExcusesOfSchool(String schoolId, LocalDate from, LocalDate to);

    /**
     * Feature 34: the requests of one employee that are still live — pending or approved — of one type, whatever the
     * day. The overlap and the balance checks read it, so a cancelled or a rejected request stops counting against
     * her.
     */
    @Query("select r from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.staffKind = ?2 and r.staffId = ?3"
            + " and r.type = ?4 and r.status in ('PENDING', 'APPROVED') order by r.day asc")
    List<Entities.StaffAttendanceRequestEntity> liveOfType(String schoolId, String staffKind, String staffId, String type);

    /**
     * Feature 34: one employee's approved leave of one type whose range starts inside the year, which is what a
     * balance is spent in. The year is the calendar year in the school's zone, and a range is counted in the year it
     * starts so no request is split or counted twice.
     */
    @Query("select r from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.staffKind = ?2 and r.staffId = ?3"
            + " and r.type = 'LEAVE' and r.status = 'APPROVED' and r.day between ?4 and ?5 order by r.day asc")
    List<Entities.StaffAttendanceRequestEntity> approvedLeaveOfYear(String schoolId, String staffKind, String staffId,
                                                                     LocalDate from, LocalDate to);

    /** Feature 34: every request of one school whose day falls in the month — the Requests report. */
    @Query("select r from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.day between ?2 and ?3"
            + " order by r.day desc, r.createdAt desc")
    List<Entities.StaffAttendanceRequestEntity> ofMonth(String schoolId, LocalDate from, LocalDate to);

    /** Feature 17's report and the employee day: how many corrections were applied to her in a range. */
    @Query("select count(r) from StaffAttendanceRequestEntity r where r.schoolId = ?1 and r.staffKind = ?2 and r.staffId = ?3"
            + " and r.type = 'CORRECTION' and r.status = 'APPROVED' and r.day between ?4 and ?5")
    long countAppliedCorrections(String schoolId, String staffKind, String staffId, LocalDate from, LocalDate to);
}
