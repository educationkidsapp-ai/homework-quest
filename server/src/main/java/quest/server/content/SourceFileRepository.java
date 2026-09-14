package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceFileRepository extends JpaRepository<Entities.SourceFileEntity, String> {
    List<Entities.SourceFileEntity> findByLessonIdOrderByCreatedAt(String lessonId);
    List<Entities.SourceFileEntity> findByFileHash(String fileHash);
}
