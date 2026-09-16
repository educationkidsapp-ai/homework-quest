package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileRepository extends JpaRepository<Entities.SourceFileEntity, String> {
    List<Entities.SourceFileEntity> findByLessonIdOrderByCreatedAt(String lessonId);
    /** Batched form of the call above — one query for a whole lessons list instead of one per row. */
    List<Entities.SourceFileEntity> findByLessonIdInOrderByLessonIdAscCreatedAtAsc(java.util.Collection<String> lessonIds);
    List<Entities.SourceFileEntity> findByFileHash(String fileHash);
}
