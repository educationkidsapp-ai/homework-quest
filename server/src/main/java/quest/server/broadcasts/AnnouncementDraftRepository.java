package quest.server.broadcasts;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Feature 22's drafts. Transactional for {@link BroadcastRepository}'s reason — the `school` filter is enabled per
 * transaction and Spring Data makes only the `CrudRepository` methods transactional on their own.
 *
 * <p>Every lookup names the school <em>and</em> the author: a draft is the leader's own unsent text and nobody else
 * ever reads it, not another author of the same school and not the author of another school. {@link #unsent} is the
 * one exception and is the sweep's, which runs from the scheduler with no scope at all and therefore names no school.
 */
@Transactional(readOnly = true)
public interface AnnouncementDraftRepository extends JpaRepository<Entities.AnnouncementDraftEntity, String> {
    /** The draft this author may send, or empty — which is a 404, whether the id is another author's or nobody's. */
    @Query("select d from AnnouncementDraftEntity d where d.schoolId = :schoolId and d.id = :id and d.authorId = :authorId")
    Optional<Entities.AnnouncementDraftEntity> findOneOfAuthor(@Param("schoolId") String schoolId, @Param("id") String id,
                                                               @Param("authorId") String authorId);

    /** What one author has prepared, newest first — the composer's own list once a screen asks for it. */
    @Query("select d from AnnouncementDraftEntity d where d.schoolId = :schoolId and d.authorId = :authorId order by d.createdAt desc")
    List<Entities.AnnouncementDraftEntity> byAuthor(@Param("schoolId") String schoolId, @Param("authorId") String authorId, Pageable page);

    /**
     * The privacy sweep's page: drafts nobody sent, older than the cutoff, across the platform. Unscoped on purpose —
     * it runs from the scheduler, where no `school` filter is enabled, so one school can never decide what another
     * school's leader still has open ({@code UploadRetention.sweepAttachments} is the same argument).
     */
    @Query("select d from AnnouncementDraftEntity d where d.broadcastId is null and d.createdAt < :cutoff order by d.createdAt asc")
    List<Entities.AnnouncementDraftEntity> unsent(@Param("cutoff") Instant cutoff, Pageable page);
}
