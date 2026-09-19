package quest.server.children;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttemptRepository extends JpaRepository<Entities.AttemptEntity, String> {
    List<Entities.AttemptEntity> findByChildIdOrderByAnsweredAtDesc(String childId);
    List<Entities.AttemptEntity> findByLessonId(String lessonId);
    /** Every attempt of a set of lessons — one query for a whole usage report, grouped by lesson in Java. */
    List<Entities.AttemptEntity> findByLessonIdIn(Collection<String> lessonIds);
    long countByChildId(String childId);

    /**
     * Every attempt of a whole class of children in one statement — §6 screen 15 computes stars, bands and "last
     * played" for each of them, and a query per child would be an N+1 that only shows up in a real school.
     */
    List<Entities.AttemptEntity> findByChildIdIn(Collection<String> childIds);

    /**
     * The same, narrowed to a set of lessons (N4.1). The gradebook grid asks about one month of a class's work, and
     * a class's whole attempt history is every lesson it has ever played — the window belongs in the statement, not
     * in a filter afterwards. `attempts(child_id, lesson_id)` (V13) is the index behind both of these.
     */
    List<Entities.AttemptEntity> findByChildIdInAndLessonIdIn(Collection<String> childIds, Collection<String> lessonIds);

    /** One child's attempts on a named set of lessons — the child page and her parent's released results. */
    List<Entities.AttemptEntity> findByChildIdAndLessonIdIn(String childId, Collection<String> lessonIds);

    /**
     * `[lessonId, how many distinct children have answered at least one stop of it]` — §4's "12/24 played" for a
     * whole week's grid in one statement. Counted in the database rather than by loading the attempts, because a
     * week of ten classes is tens of thousands of rows and the grid needs one number from each.
     */
    @Query("select a.lessonId, count(distinct a.childId) from AttemptEntity a where a.lessonId in :lessonIds group by a.lessonId")
    List<Object[]> countPlayersByLessonIdIn(@Param("lessonIds") Collection<String> lessonIds);
}
