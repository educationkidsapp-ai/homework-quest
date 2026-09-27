package quest.server.broadcasts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for `ChildRepository`'s reason: the `school` filter is enabled per transaction and Spring Data makes
 * only the `CrudRepository` methods transactional on their own. Every query names `school_id` as well, because a
 * parent's feed runs with no filter at all and must still be scoped to her child's school.
 */
@Transactional(readOnly = true)
public interface BroadcastRepository extends JpaRepository<Entities.BroadcastEntity, String> {
    /** One school's live broadcasts, newest first: every feed filters one page of these by the caller's own reach. */
    @Query("select b from BroadcastEntity b where b.schoolId = :schoolId and (b.expiresAt is null or b.expiresAt > :now) order by b.createdAt desc")
    List<Entities.BroadcastEntity> live(@Param("schoolId") String schoolId, @Param("now") Instant now, Pageable page);

    /** What one author posted, newest first — her own composer list, expired rows included. */
    @Query("select b from BroadcastEntity b where b.schoolId = :schoolId and b.authorUserId = :authorId order by b.createdAt desc")
    List<Entities.BroadcastEntity> byAuthor(@Param("schoolId") String schoolId, @Param("authorId") String authorId, Pageable page);

    /**
     * The weekly plan of <strong>one</strong> department and week, which a re-post replaces (there is at most one).
     * The curriculum is matched exactly, and a null one matches only a null one: `(:curriculum is null or …)` read as
     * "every department" and would have let one manager's replacement delete the other department's plan and its read
     * marks. A plan with no department cannot be posted at all ({@code BroadcastService.managerPost}), so the null
     * branch is there to be closed rather than to be used.
     */
    @Query("select b from BroadcastEntity b where b.schoolId = :schoolId and b.kind = 'weekly_plan'"
            + " and b.weekStart = :week and ((:curriculum is null and b.curriculum is null) or b.curriculum = :curriculum)"
            + " order by b.createdAt desc")
    List<Entities.BroadcastEntity> weeklyPlans(@Param("schoolId") String schoolId, @Param("week") LocalDate week,
                                               @Param("curriculum") String curriculum);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select b from BroadcastEntity b where b.id = :id")
    Optional<Entities.BroadcastEntity> findOneById(@Param("id") String id);
}
