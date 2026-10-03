package quest.server.files;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for `BroadcastRepository`'s reason: the `school` filter is enabled per transaction. */
@Transactional(readOnly = true)
public interface AttachmentRepository extends JpaRepository<Entities.AttachmentEntity, String> {
    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select a from AttachmentEntity a where a.id = :id")
    Optional<Entities.AttachmentEntity> findOneById(@Param("id") String id);

    /** B5: the chat files whose message still exists — what {@code UploadRetention} must never sweep. */
    @Query("select a.id from AttachmentEntity a where a.messageId is not null and exists (select m.id from ChatMessageEntity m where m.id = a.messageId)")
    java.util.Set<String> sentWithLiveMessage();
}
