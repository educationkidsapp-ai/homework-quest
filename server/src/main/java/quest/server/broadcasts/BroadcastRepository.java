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
     * The weekly plan of <strong>one</strong> department, week and grade (V23), which a re-post replaces — there is at
     * most one, and a grade's plan and the department's all-grades plan (`grade` null) are two different rows.
     * The curriculum is matched exactly, and a null one matches only a null one: `(:curriculum is null or …)` read as
     * "every department" and would have let one manager's replacement delete the other department's plan and its read
     * marks. A plan with no department cannot be posted at all ({@code BroadcastService.managerPost}), so the null
     * branch is there to be closed rather than to be used. `grade` is matched the same way and its null branch is a
     * real one: an all-grades plan is exactly the row whose `grade` is null.
     */
    @Query("select b from BroadcastEntity b where b.schoolId = :schoolId and b.kind = 'weekly_plan'"
            + " and b.weekStart = :week and ((:curriculum is null and b.curriculum is null) or b.curriculum = :curriculum)"
            + " and ((:grade is null and b.grade is null) or b.grade = :grade)"
            + " order by b.createdAt desc")
    List<Entities.BroadcastEntity> weeklyPlans(@Param("schoolId") String schoolId, @Param("week") LocalDate week,
                                               @Param("curriculum") String curriculum, @Param("grade") Integer grade);

    /**
     * MG1: one school's weekly plans over a window, newest week first then newest post — the archive's single read,
     * which every one of the three archives then filters by the caller's own reach. Expiry is not named at all: a
     * feed may hide a plan whose week has gone by and the archive is exactly the screen that must not.
     */
    @Query("select b from BroadcastEntity b where b.schoolId = :schoolId and b.kind = 'weekly_plan'"
            + " and b.weekStart >= :from and b.weekStart <= :to order by b.weekStart desc, b.createdAt desc")
    List<Entities.BroadcastEntity> plansBetween(@Param("schoolId") String schoolId, @Param("from") LocalDate from,
                                                @Param("to") LocalDate to, Pageable page);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select b from BroadcastEntity b where b.id = :id")
    Optional<Entities.BroadcastEntity> findOneById(@Param("id") String id);
}
