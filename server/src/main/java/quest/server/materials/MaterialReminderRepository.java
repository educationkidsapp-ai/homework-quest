package quest.server.materials;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). */
@Transactional(readOnly = true)
public interface MaterialReminderRepository extends JpaRepository<Entities.MaterialReminderEntity, String> {
    /**
     * Claims one teacher's reminder for one week: 1 when this call wrote the key and may send it, 0 when an earlier
     * run — or another instance running the job at the same moment — already did. One statement, as
     * {@link quest.server.staffattendance.StaffAttendanceAlertRepository#claim}.
     */
    @Modifying @Transactional
    @Query(nativeQuery = true, value = "insert into material_reminders (id, school_id, teacher_id, week_start, sent_at)"
            + " values (:#{#r.id}, :#{#r.schoolId}, :#{#r.teacherId}, :#{#r.weekStart}, :#{#r.sentAt})"
            + " on conflict do nothing")
    int claim(@Param("r") Entities.MaterialReminderEntity reminder);

    java.util.List<Entities.MaterialReminderEntity> findBySchoolIdAndWeekStart(String schoolId, java.time.LocalDate weekStart);
}
