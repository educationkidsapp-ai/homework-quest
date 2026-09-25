package quest.server.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayRepository extends JpaRepository<Entities.PlayEntity, String> {
    List<Entities.PlayEntity> findByLessonIdOrderByLevelAscVariantAsc(String lessonId);
    /** Batched form of the call above — one query for a whole map instead of one per lesson. */
    List<Entities.PlayEntity> findByLessonIdInOrderByLessonIdAscLevelAscVariantAsc(Collection<String> lessonIds);
    Optional<Entities.PlayEntity> findByLessonIdAndLevelAndVariant(String lessonId, int level, int variant);

    /**
     * `[level, variant, number of stops]` for one lesson — what the `/status` poll (E1) shows of the levels, without
     * loading a single `play_json` or decoding one. A level with no stops yet still has a row, hence the outer join.
     */
    @org.springframework.data.jpa.repository.Query("""
            select p.level, p.variant, count(s.id) from PlayEntity p
            left join StopEntity s on s.playId = p.id
            where p.lessonId = :lessonId group by p.level, p.variant order by p.level asc, p.variant asc""")
    List<Object[]> playSizes(@org.springframework.data.repository.query.Param("lessonId") String lessonId);
}
