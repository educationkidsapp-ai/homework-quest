package quest.server.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * V55 (feature 32): the map from a section to its one channel thread. Transactional for `ChildRepository`'s reason —
 * the `school` filter is enabled per transaction — and read by {@code classId} only, which is a section of the
 * caller's own school because the filter has already reduced the table to it.
 */
@Transactional(readOnly = true)
public interface ClassChannelRepository extends JpaRepository<Entities.ClassChannelEntity, String> {
    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (`ClassRepository.findOneById`). */
    @Query("select c from ClassChannelEntity c where c.classId = :classId")
    Optional<Entities.ClassChannelEntity> findOneByClassId(@Param("classId") String classId);
}
