package quest.server.content;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonStepRepository extends JpaRepository<Entities.LessonStepEntity, String> {
    List<Entities.LessonStepEntity> findByLessonIdOrderByPosition(String lessonId);
    List<Entities.LessonStepEntity> findByStatus(String status);
}
