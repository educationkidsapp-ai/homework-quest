package quest.server.content;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StopRepository extends JpaRepository<Entities.StopEntity, String> {
    List<Entities.StopEntity> findByPlayIdOrderByPosition(String playId);
    List<Entities.StopEntity> findByLessonId(String lessonId);
    /** Every stop of a set of lessons — one query for a whole usage report, grouped by lesson in Java. */
    List<Entities.StopEntity> findByLessonIdIn(Collection<String> lessonIds);

    /** `[lessonId, number of stops in its Level 1 play]` for a set of lessons — one query for a whole map. */
    @Query("select s.lessonId, count(s) from StopEntity s, PlayEntity p where p.id = s.playId and p.lessonId in :lessonIds and p.level = 1 and p.variant = 0 group by s.lessonId")
    List<Object[]> countLevelOneStops(@Param("lessonIds") Collection<String> lessonIds);
}
