package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonCompletionRepository extends JpaRepository<Entities.LessonCompletionEntity, Entities.LessonCompletionId> {
    List<Entities.LessonCompletionEntity> findByChildId(String childId);
    List<Entities.LessonCompletionEntity> findByLessonId(String lessonId);
    long countDistinctChildIdByLessonId(String lessonId);
}
