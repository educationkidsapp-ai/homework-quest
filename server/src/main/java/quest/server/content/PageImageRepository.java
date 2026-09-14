package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PageImageRepository extends JpaRepository<Entities.PageImageEntity, String> { List<Entities.PageImageEntity> findByLessonIdOrderByPageNumber(String lessonId); }
