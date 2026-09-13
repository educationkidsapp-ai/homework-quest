package quest.server.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import quest.server.domain.UploadEntity;

public interface UploadRepository extends JpaRepository<UploadEntity, String> {
    List<UploadEntity> findByLessonIdAndDeletedAtIsNull(String lessonId);
}
