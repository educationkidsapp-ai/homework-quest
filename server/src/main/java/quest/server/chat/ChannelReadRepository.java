package quest.server.chat;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * V55 (feature 32): one parent's read mark in one channel, V22's `broadcast_reads` one feature over. Transactional
 * for `ChildRepository`'s reason; the `school` filter scopes every read, and the parent is the caller herself —
 * never a request parameter.
 */
@Transactional(readOnly = true)
public interface ChannelReadRepository extends JpaRepository<Entities.ChannelReadEntity, String> {
    @Query("select r from ChannelReadEntity r where r.threadId = :threadId and r.parentId = :parentId")
    Optional<Entities.ChannelReadEntity> findOne(@Param("threadId") String threadId, @Param("parentId") String parentId);
}
