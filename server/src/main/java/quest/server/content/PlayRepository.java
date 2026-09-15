package quest.server.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayRepository extends JpaRepository<Entities.PlayEntity, String> {
    List<Entities.PlayEntity> findByLessonIdOrderByLevelAscVariantAsc(String lessonId);
    /** Batched form of the call above — one query for a whole map instead of one per lesson. */
    List<Entities.PlayEntity> findByLessonIdInOrderByLessonIdAscLevelAscVariantAsc(Collection<String> lessonIds);
    Optional<Entities.PlayEntity> findByLessonIdAndLevelAndVariant(String lessonId, int level, int variant);
}
