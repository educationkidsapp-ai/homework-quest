package quest.server.children;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<Entities.AttemptEntity, String> {
    List<Entities.AttemptEntity> findByChildIdOrderByAnsweredAtDesc(String childId);
    List<Entities.AttemptEntity> findByLessonId(String lessonId);
    /** Every attempt of a set of lessons — one query for a whole usage report, grouped by lesson in Java. */
    List<Entities.AttemptEntity> findByLessonIdIn(Collection<String> lessonIds);
    long countByChildId(String childId);

    /**
     * Every attempt of a whole class of children in one statement — §6 screen 15 computes stars, bands and "last
     * played" for each of them, and a query per child would be an N+1 that only shows up in a real school.
     */
    List<Entities.AttemptEntity> findByChildIdIn(Collection<String> childIds);
}
