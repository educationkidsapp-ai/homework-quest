package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayRepository extends JpaRepository<Entities.PlayEntity, String> {
    List<Entities.PlayEntity> findByLessonIdOrderByLevelAscVariantAsc(String lessonId);
    Optional<Entities.PlayEntity> findByLessonIdAndLevelAndVariant(String lessonId, int level, int variant);
}
