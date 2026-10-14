package quest.server.staffattendance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). */
@Transactional(readOnly = true)
public interface StaffAttendanceAlertRepository extends JpaRepository<Entities.StaffAttendanceAlertEntity, String> {
    /**
     * Claims one notification: 1 when this call wrote the key and may send it, 0 when it was already sent. One
     * statement, so two instances running the job at the same moment cannot both decide it is theirs.
     */
    @Modifying @Transactional
    @Query(nativeQuery = true, value = "insert into staff_attendance_alerts (id, school_id, staff_kind, staff_id, work_day, type, sent_at)"
            + " values (:#{#a.id}, :#{#a.schoolId}, :#{#a.staffKind}, :#{#a.staffId}, :#{#a.day}, :#{#a.type}, :#{#a.sentAt})"
            + " on conflict do nothing")
    int claim(@Param("a") Entities.StaffAttendanceAlertEntity alert);
}
