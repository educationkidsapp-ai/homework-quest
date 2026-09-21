package quest.server.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paging is by the (created_at, id) pair, newest first, so a burst that lands in one microsecond still pages without
 * a gap; `ChatService` reverses a page so the client gets oldest first. Transactional for `ChildRepository`'s reason.
 */
@Transactional(readOnly = true)
public interface ChatMessageRepository extends JpaRepository<Entities.ChatMessageEntity, String> {
    @Query("select m from ChatMessageEntity m where m.threadId = :threadId order by m.createdAt desc, m.id desc")
    List<Entities.ChatMessageEntity> newest(@Param("threadId") String threadId, Pageable page);

    @Query("select m from ChatMessageEntity m where m.threadId = :threadId and (m.createdAt < :at or (m.createdAt = :at and m.id < :id)) order by m.createdAt desc, m.id desc")
    List<Entities.ChatMessageEntity> before(@Param("threadId") String threadId, @Param("at") Instant at, @Param("id") String id, Pageable page);

    @Query("select m from ChatMessageEntity m where m.threadId = :threadId and (m.createdAt > :at or (m.createdAt = :at and m.id > :id)) order by m.createdAt asc, m.id asc")
    List<Entities.ChatMessageEntity> since(@Param("threadId") String threadId, @Param("at") Instant at, @Param("id") String id, Pageable page);

    /** The newest message of each thread in one statement, for the thread lists. */
    @Query("select m from ChatMessageEntity m where m.threadId in :threadIds and m.createdAt = (select max(n.createdAt) from ChatMessageEntity n where n.threadId = m.threadId)")
    List<Entities.ChatMessageEntity> lastOf(@Param("threadIds") List<String> threadIds);

    @Query("select m from ChatMessageEntity m where m.id = :id")
    Optional<Entities.ChatMessageEntity> findOneById(@Param("id") String id);

    /** Everything the other party wrote and nobody has read yet, read now. */
    @Modifying @Transactional
    @Query("update ChatMessageEntity m set m.readAt = :at where m.threadId = :threadId and m.senderRole = :role and m.readAt is null")
    int markRead(@Param("threadId") String threadId, @Param("role") String role, @Param("at") Instant at);

    @Modifying @Transactional
    @Query("delete from ChatMessageEntity m where m.threadId in :threadIds")
    int deleteByThreads(@Param("threadIds") List<String> threadIds);
}
