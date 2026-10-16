package quest.server.hints;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import quest.server.hints.Entities.StopHintsEntity;

/**
 * Feature 30's hints. Every read is scoped by the `school` filter and so by the caller's own school —
 * `TenantArchitectureTest` is why the interface is {@code @Transactional}: the filter is enabled per transaction, and
 * a derived query that opened its own would run with it off.
 */
@Transactional
public interface StopHintsRepository extends JpaRepository<StopHintsEntity, String> {
    List<StopHintsEntity> findByLessonId(String lessonId);
    /** One query for a whole map or a whole page of lessons instead of one per lesson. */
    List<StopHintsEntity> findByLessonIdIn(Collection<String> lessonIds);
}
