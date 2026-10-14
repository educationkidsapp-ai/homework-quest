package quest.server.chat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). */
@Transactional(readOnly = true)
public interface ChatAfterHoursRepository extends JpaRepository<Entities.ChatAfterHoursEntity, String> {
    /**
     * Whom {@link OfficeHoursSummaryJob} owes a summary in one school: every recipient with a row that has fallen due
     * and has not been summarised yet. The school is named, never read from the tenant — the job has no caller.
     */
    @Query("select distinct r.userId from ChatAfterHoursEntity r"
            + " where r.schoolId = :schoolId and r.summarizedAt is null and r.dueDay <= :today")
    List<String> dueRecipients(@Param("schoolId") String schoolId, @Param("today") LocalDate today);

    /**
     * Claims one recipient's due rows: the count is how many messages her summary is about, and 0 means an earlier run
     * (or another instance) already claimed them and this one sends nothing. One statement, so two runs at the same
     * moment cannot both decide the rows are theirs.
     */
    @Modifying @Transactional
    @Query("update ChatAfterHoursEntity r set r.summarizedAt = :now"
            + " where r.schoolId = :schoolId and r.userId = :userId and r.summarizedAt is null and r.dueDay <= :today")
    int claim(@Param("schoolId") String schoolId, @Param("userId") String userId,
              @Param("today") LocalDate today, @Param("now") Instant now);
}
