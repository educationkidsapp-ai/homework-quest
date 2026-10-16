package quest.server.hints;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import quest.server.hints.Entities.PracticePlayEntity;

/**
 * Feature 30's practice sets. Scoped by the `school` filter like every tenant read, and {@code @Transactional} for
 * the reason {@link StopHintsRepository} is.
 */
@Transactional
public interface PracticePlayRepository extends JpaRepository<PracticePlayEntity, String> {
    List<PracticePlayEntity> findByLessonIdOrderBySkillIdAsc(String lessonId);
    List<PracticePlayEntity> findByLessonIdIn(Collection<String> lessonIds);

    /**
     * `[lessonId, skillId, the play's variant]` for the <em>approved</em> practice sets of a set of lessons — what the
     * map's review islands need, in one query and without decoding a single play.
     */
    @org.springframework.data.jpa.repository.Query("""
            select r.lessonId, r.skillId, p.variant from PracticePlayEntity r, PlayEntity p
            where p.id = r.playId and r.lessonId in :lessonIds and r.approvedAt is not null
            order by p.variant asc""")
    List<Object[]> approvedByLesson(@org.springframework.data.repository.query.Param("lessonIds") Collection<String> lessonIds);
}
