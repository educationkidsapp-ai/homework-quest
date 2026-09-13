package quest.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import quest.server.domain.LessonEntity;

public interface LessonRepository extends JpaRepository<LessonEntity, String> {}
