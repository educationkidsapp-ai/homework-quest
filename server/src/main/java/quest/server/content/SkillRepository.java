package quest.server.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Entities.SkillEntity, String> {
    List<Entities.SkillEntity> findByLessonIdOrderByPosition(String lessonId);
    List<Entities.SkillEntity> findByLessonIdAndConfirmedTrueOrderByPosition(String lessonId);
    /** Batched form of the call above — one query for a whole map instead of one per lesson. */
    List<Entities.SkillEntity> findByLessonIdInAndConfirmedTrueOrderByLessonIdAscPositionAsc(Collection<String> lessonIds);
}
