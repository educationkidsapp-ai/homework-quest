package quest.server.content;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LessonRepository extends JpaRepository<Entities.LessonEntity, String> {
    List<Entities.LessonEntity> findByCourseIdAndStatusAndDateBetweenOrderByDateAsc(String courseId, String status, LocalDate from, LocalDate to);
    List<Entities.LessonEntity> findByCourseIdAndStatus(String courseId, String status);
    List<Entities.LessonEntity> findAllByOrderByDateDescCreatedAtDesc();
    List<Entities.LessonEntity> findBySourceHash(String sourceHash);
}
