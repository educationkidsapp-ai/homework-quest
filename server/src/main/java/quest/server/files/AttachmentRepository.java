package quest.server.files;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for `BroadcastRepository`'s reason: the `school` filter is enabled per transaction. */
@Transactional(readOnly = true)
public interface AttachmentRepository extends JpaRepository<Entities.AttachmentEntity, String> {
    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select a from AttachmentEntity a where a.id = :id")
    Optional<Entities.AttachmentEntity> findOneById(@Param("id") String id);

    /**
     * B5: one page of what {@code UploadRetention} sweeps — older than {@code cutoff}, carried by no broadcast, and not
     * sent with a chat message that still exists. Oldest first.
     */
    @Query("select a from AttachmentEntity a where a.createdAt < :cutoff"
            + " and not exists (select b.id from BroadcastEntity b where b.attachmentId = a.id)"
            + " and (a.messageId is null or not exists (select m.id from ChatMessageEntity m where m.id = a.messageId))"
            + " order by a.createdAt, a.id")
    java.util.List<Entities.AttachmentEntity> orphans(@Param("cutoff") java.time.Instant cutoff, org.springframework.data.domain.Pageable page);

    /**
     * B5 review: binds the sender's own unsent chat uploads to one message in a single conditional statement. The row
     * count is the answer — fewer than asked means one of them was sent (or taken) meanwhile, and the caller refuses the
     * whole send, so two sends racing for one file can never both have it.
     */
    @Modifying @Transactional
    @Query("update AttachmentEntity a set a.messageId = :messageId where a.id in :ids and a.messageId is null"
            + " and a.uploadedBy = :uploader and a.schoolId = :schoolId and a.purpose = 'chat'")
    int bind(@Param("ids") java.util.Collection<String> ids, @Param("messageId") String messageId,
             @Param("uploader") String uploader, @Param("schoolId") String schoolId);
}
