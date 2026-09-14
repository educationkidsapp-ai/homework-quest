package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Entities.AttemptEntity, String> {
    List<Entities.AttemptEntity> findByChildIdOrderByAnsweredAtDesc(String childId);
    List<Entities.AttemptEntity> findByLessonId(String lessonId);
    long countByChildId(String childId);
}
