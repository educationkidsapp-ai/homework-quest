package quest.server.children;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Entities.AttemptEntity, String> {
    List<Entities.AttemptEntity> findByChildIdOrderByAnsweredAtDesc(String childId);
    List<Entities.AttemptEntity> findByLessonId(String lessonId);
    /** Every attempt of a set of lessons — one query for a whole usage report, grouped by lesson in Java. */
    List<Entities.AttemptEntity> findByLessonIdIn(Collection<String> lessonIds);
    /** Every attempt of a set of children — one query for a whole class's skill bands (see `ProgressService`). */
    List<Entities.AttemptEntity> findByChildIdInOrderByAnsweredAtDesc(Collection<String> childIds);
    long countByChildId(String childId);
}
