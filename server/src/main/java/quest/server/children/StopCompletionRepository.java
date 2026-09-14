package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopCompletionRepository extends JpaRepository<Entities.StopCompletionEntity, Entities.StopCompletionId> {
    List<Entities.StopCompletionEntity> findByChildIdAndLessonId(String childId, String lessonId);
    List<Entities.StopCompletionEntity> findByChildId(String childId);
}
