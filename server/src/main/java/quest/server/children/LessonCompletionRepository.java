package quest.server.children;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonCompletionRepository extends JpaRepository<Entities.LessonCompletionEntity, Entities.LessonCompletionId> {
    List<Entities.LessonCompletionEntity> findByChildId(String childId);
    List<Entities.LessonCompletionEntity> findByLessonId(String lessonId);
    /** Every completion of a set of lessons — one query for a whole usage report, grouped by lesson in Java. */
    List<Entities.LessonCompletionEntity> findByLessonIdIn(Collection<String> lessonIds);
    long countDistinctChildIdByLessonId(String lessonId);
}
