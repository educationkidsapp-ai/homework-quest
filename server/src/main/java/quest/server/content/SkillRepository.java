package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Entities.SkillEntity, String> {
    List<Entities.SkillEntity> findByLessonIdOrderByPosition(String lessonId);
    List<Entities.SkillEntity> findByLessonIdAndConfirmedTrueOrderByPosition(String lessonId);
}
