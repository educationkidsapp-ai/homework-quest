package quest.server.exams;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason {@link quest.server.content.LessonRepository} gives: the `school` filter is enabled
 * per transaction, and a derived query without one would run with the filter off.
 */
@Transactional(readOnly = true)
public interface ExamSettingsRepository extends JpaRepository<Entities.ExamSettingsEntity, String> {

    /** Scoped by the filter like every other read here; `findById` would bypass it (Hibernate does not filter `find`). */
    @Query("select e from ExamSettingsEntity e where e.lessonId = :lessonId")
    Optional<Entities.ExamSettingsEntity> findOneByLessonId(@Param("lessonId") String lessonId);

    /** The settings of a page of lessons at once — what keeps the map and the gradebook free of a query per exam. */
    @Query("select e from ExamSettingsEntity e where e.lessonId in :lessonIds")
    List<Entities.ExamSettingsEntity> findByLessonIdIn(@Param("lessonIds") Collection<String> lessonIds);

    /**
     * Every window that has closed, for {@link ExamReleaseSweep}. It runs on the scheduler with no caller and
     * therefore no school ({@link quest.server.tenancy.TenantContext} returns null and the filter stays off), which
     * is what {@link quest.server.analysis.PipelineWatchdog} does and for the same reason: a window closes for
     * whichever school it belongs to, and the sweep reads one column and writes none of its own.
     */
    List<Entities.ExamSettingsEntity> findByReleaseModeAndClosesAtLessThan(String releaseMode, Instant now);
}
