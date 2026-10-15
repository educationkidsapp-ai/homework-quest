package quest.server.documents;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 26's summaries. Transactional for {@code AnnouncementDraftRepository}'s reason — the `school` filter is
 * enabled per transaction and Spring Data makes only the `CrudRepository` methods transactional on their own.
 *
 * <p>Every lookup names the school <em>and</em> the author: what a leader read is hers, and nobody else ever reads it,
 * not another manager of the same school and not a manager of another school. {@link #olderThan} is the one exception
 * and is the sweep's, which runs from the scheduler with no scope at all and therefore names no school.
 */
@Transactional(readOnly = true)
public interface DocumentSummaryRepository extends JpaRepository<Entities.DocumentSummaryEntity, String> {
    /** The summary this author may read, or empty — which is a 404, whether the id is another author's or nobody's. */
    @Query("select d from DocumentSummaryEntity d where d.schoolId = :schoolId and d.id = :id and d.authorId = :authorId")
    Optional<Entities.DocumentSummaryEntity> findOneOfAuthor(@Param("schoolId") String schoolId, @Param("id") String id,
                                                             @Param("authorId") String authorId);

    /** What one author has read, newest first — her page's list, capped by the caller's {@link Pageable}. */
    @Query("select d from DocumentSummaryEntity d where d.schoolId = :schoolId and d.authorId = :authorId order by d.createdAt desc")
    List<Entities.DocumentSummaryEntity> byAuthor(@Param("schoolId") String schoolId, @Param("authorId") String authorId, Pageable page);

    /**
     * The retention sweep's page: summaries older than the cutoff, across the platform. Unscoped on purpose — it runs
     * from the scheduler, where no `school` filter is enabled, so one school can never decide how long another
     * school's leader keeps what she read (`VoiceDraftRepository.unsent` is the same argument).
     */
    @Query("select d from DocumentSummaryEntity d where d.createdAt < :cutoff order by d.createdAt asc")
    List<Entities.DocumentSummaryEntity> olderThan(@Param("cutoff") Instant cutoff, Pageable page);
}
