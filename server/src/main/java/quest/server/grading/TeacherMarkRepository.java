package quest.server.grading;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional throughout for the reason {@link quest.server.children.ChildRepository} gives: a derived method runs
 * in a session of its own otherwise and never reaches the transaction manager that switches the `school` filter on.
 */
@Transactional(readOnly = true)
public interface TeacherMarkRepository extends JpaRepository<Entities.TeacherMarkEntity, String> {
    Optional<Entities.TeacherMarkEntity> findByChildIdAndLessonIdAndStopId(String childId, String lessonId, String stopId);

    List<Entities.TeacherMarkEntity> findByLessonId(String lessonId);

    List<Entities.TeacherMarkEntity> findByChildIdOrderByMarkedAtDesc(String childId);

    /**
     * Every mark of a set of lessons in one statement. The gradebook grid is children × lessons and a query per cell
     * — or even per lesson — is the N+1 `GradebookQueryCountTest` exists to refuse.
     */
    List<Entities.TeacherMarkEntity> findByLessonIdIn(Collection<String> lessonIds);
}
