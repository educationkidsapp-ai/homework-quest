package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StopRepository extends JpaRepository<Entities.StopEntity, String> {
    List<Entities.StopEntity> findByPlayIdOrderByPosition(String playId);
    List<Entities.StopEntity> findByLessonId(String lessonId);
}
