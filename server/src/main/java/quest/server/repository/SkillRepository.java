package quest.server.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import quest.server.domain.SkillEntity;

public interface SkillRepository extends JpaRepository<SkillEntity, String> {
    List<SkillEntity> findByLessonIdOrderByPosition(String lessonId);
    List<SkillEntity> findByLessonIdAndConfirmedTrueOrderByPosition(String lessonId);
}
