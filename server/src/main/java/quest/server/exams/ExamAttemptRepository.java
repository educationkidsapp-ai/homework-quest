package quest.server.exams;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason {@link ExamSettingsRepository} gives: the `school` filter is enabled per transaction. */
@Transactional(readOnly = true)
public interface ExamAttemptRepository extends JpaRepository<Entities.ExamAttemptEntity, String> {

    /**
     * One child's sitting of one exam — the row the unique index (child, exam) guarantees there is at most one of.
     *
     * <p>Read through a query rather than by id so that the `school` filter applies; the child side reaches it with
     * a parent's token, which carries no school scope at all, and the teacher side with one that does.
     */
    @Query("select a from ExamAttemptEntity a where a.childId = :childId and a.lessonId = :lessonId")
    Optional<Entities.ExamAttemptEntity> findOne(@Param("childId") String childId, @Param("lessonId") String lessonId);

    /** Every sitting of one exam, for the results table: one statement whatever the size of the class. */
    List<Entities.ExamAttemptEntity> findByLessonId(String lessonId);

    /**
     * `[lessonId, sittings]` for a page of exams — the Exams tab's `sat` column for a whole class in one
     * statement. Counted in the database rather than by loading the rows: the tab wants one integer per exam, and
     * the unique index on (child, exam) means the count already <em>is</em> the number of children who sat it.
     */
    @Query("select a.lessonId, count(a) from ExamAttemptEntity a where a.lessonId in :lessonIds group by a.lessonId")
    List<Object[]> countByLessonIdIn(@Param("lessonIds") Collection<String> lessonIds);

    /** Every sitting of a page of exams, so the gradebook and the map never pay a query per exam. */
    @Query("select a from ExamAttemptEntity a where a.childId = :childId and a.lessonId in :lessonIds")
    List<Entities.ExamAttemptEntity> findByChildIdAndLessonIdIn(@Param("childId") String childId,
                                                                @Param("lessonIds") Collection<String> lessonIds);
}
