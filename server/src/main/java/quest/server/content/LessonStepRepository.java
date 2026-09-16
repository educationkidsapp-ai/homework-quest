package quest.server.content;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonStepRepository extends JpaRepository<Entities.LessonStepEntity, String> {
    List<Entities.LessonStepEntity> findByLessonIdOrderByPosition(String lessonId);
    /** Batched form of the call above — one query for a whole lessons list instead of one per row. */
    List<Entities.LessonStepEntity> findByLessonIdInOrderByLessonIdAscPositionAsc(java.util.Collection<String> lessonIds);
    List<Entities.LessonStepEntity> findByStatus(String status);
}
