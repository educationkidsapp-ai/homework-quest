package quest.server.brief;

import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`); every query also
 * names the school, because {@link MorningBriefJob} has no request and therefore no filter.
 */
@Transactional(readOnly = true)
public interface MorningBriefRepository extends JpaRepository<Entities.MorningBriefEntity, String> {
    Optional<Entities.MorningBriefEntity> findOneBySchoolIdAndUserIdAndDay(String schoolId, String userId, LocalDate day);

    /**
     * Claims one leader's brief for one day: 1 when this call wrote the row and so owns the model call, 0 when
     * another run (or another instance, or a reloaded Home) already has it. One statement against V47's unique key,
     * exactly as {@link quest.server.staffattendance.StaffAttendanceAlertRepository#claim} does for feature 16, so
     * two callers cannot both decide the brief is theirs to generate.
     *
     * <p>The row inserted already carries the deterministic template, which is what makes the ordering safe: the
     * claim comes <em>before</em> the model call, so a leader who opens her Home while the model is still thinking
     * reads the template rather than nothing, and {@link MorningBriefService} replaces it when an answer is accepted.
     */
    @Modifying @Transactional
    @Query(nativeQuery = true, value = "insert into morning_briefs (id, school_id, user_id, brief_day, facts_json, text_ar, text_en, source, created_at)"
            + " values (:#{#b.id}, :#{#b.schoolId}, :#{#b.userId}, :#{#b.day}, :#{#b.factsJson}, :#{#b.textAr}, :#{#b.textEn}, :#{#b.source}, :#{#b.createdAt})"
            + " on conflict do nothing")
    int claim(@Param("b") Entities.MorningBriefEntity brief);
}
