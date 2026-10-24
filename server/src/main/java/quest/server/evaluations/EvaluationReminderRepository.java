package quest.server.evaluations;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). */
@Transactional(readOnly = true)
public interface EvaluationReminderRepository extends JpaRepository<Entities.EvaluationReminderEntity, String> {
    /**
     * Claims one teacher's reminder for one round: 1 when this call wrote the key and may send it, 0 when an earlier
     * run — or another instance running the job at the same moment — already did. One statement, as
     * {@link quest.server.materials.MaterialReminderRepository#claim}.
     */
    @Modifying @Transactional
    @Query(nativeQuery = true, value = "insert into evaluation_reminders (id, school_id, round_id, teacher_id, sent_at)"
            + " values (:#{#r.id}, :#{#r.schoolId}, :#{#r.roundId}, :#{#r.teacherId}, :#{#r.sentAt})"
            + " on conflict do nothing")
    int claim(@Param("r") Entities.EvaluationReminderEntity reminder);

    List<Entities.EvaluationReminderEntity> findBySchoolIdAndRoundId(String schoolId, String roundId);
}
