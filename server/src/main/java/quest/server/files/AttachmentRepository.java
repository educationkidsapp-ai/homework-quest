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
}
