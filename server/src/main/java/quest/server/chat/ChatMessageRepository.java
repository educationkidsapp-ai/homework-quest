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

    /** Feature 27: the other end of the same page — a complaint's first message, which is all the triage is given. */
    @Query("select m from ChatMessageEntity m where m.threadId = :threadId order by m.createdAt asc, m.id asc")
    List<Entities.ChatMessageEntity> oldest(@Param("threadId") String threadId, Pageable page);

    @Query("select m from ChatMessageEntity m where m.threadId = :threadId and (m.createdAt < :at or (m.createdAt = :at and m.id < :id)) order by m.createdAt desc, m.id desc")
    List<Entities.ChatMessageEntity> before(@Param("threadId") String threadId, @Param("at") Instant at, @Param("id") String id, Pageable page);

    @Query("select m from ChatMessageEntity m where m.threadId = :threadId and (m.createdAt > :at or (m.createdAt = :at and m.id > :id)) order by m.createdAt asc, m.id asc")
    List<Entities.ChatMessageEntity> since(@Param("threadId") String threadId, @Param("at") Instant at, @Param("id") String id, Pageable page);

    /** The newest message of each thread in one statement, for the thread lists. */
    @Query("select m from ChatMessageEntity m where m.threadId in :threadIds and m.createdAt = (select max(n.createdAt) from ChatMessageEntity n where n.threadId = m.threadId)")
    List<Entities.ChatMessageEntity> lastOf(@Param("threadIds") List<String> threadIds);

    /** B5b: what this sender already wrote into this thread under this `clientId` — the retry's answer. */
    @Query("select m from ChatMessageEntity m where m.threadId = :threadId and m.senderId = :senderId and m.clientId = :clientId order by m.createdAt")
    List<Entities.ChatMessageEntity> sentAs(@Param("threadId") String threadId, @Param("senderId") String senderId, @Param("clientId") String clientId);

    @Query("select m from ChatMessageEntity m where m.id = :id")
    Optional<Entities.ChatMessageEntity> findOneById(@Param("id") String id);

    /**
     * Feature 32: how many posts of a channel one parent has not read — everything after her mark, which is the
     * (created_at, id) pair the rest of this class pages by, and nothing she has had taken down since.
     */
    @Query("select count(m) from ChatMessageEntity m where m.threadId = :threadId and m.deletedAt is null"
            + " and (m.createdAt > :at or (m.createdAt = :at and m.id > :id))")
    long countAfter(@Param("threadId") String threadId, @Param("at") Instant at, @Param("id") String id);

    /** The same count for a parent who has never marked the channel read: every post still standing. */
    @Query("select count(m) from ChatMessageEntity m where m.threadId = :threadId and m.deletedAt is null")
    long countIn(@Param("threadId") String threadId);

    /**
     * Feature 32: one channel post taken down — by its author or by the Admin, which the caller has already decided.
     * One conditional statement, so a second delete of the same post answers 0 and the row keeps the first one's
     * author and time; the body and the attachment list are cleared here, because a post nobody may read any more
     * must not travel in a page as text.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true) @Transactional
    @Query("update ChatMessageEntity m set m.deletedAt = :at, m.deletedBy = :by, m.body = '', m.attachments = null"
            + " where m.id = :id and m.deletedAt is null")
    int softDelete(@Param("id") String id, @Param("at") Instant at, @Param("by") String by);

    /** Everything the other party wrote and nobody has read yet, read now. */
    @Modifying @Transactional
    @Query("update ChatMessageEntity m set m.readAt = :at where m.threadId = :threadId and m.senderRole = :role and m.readAt is null")
    int markRead(@Param("threadId") String threadId, @Param("role") String role, @Param("at") Instant at);

    @Modifying @Transactional
    @Query("delete from ChatMessageEntity m where m.threadId in :threadIds")
    int deleteByThreads(@Param("threadIds") List<String> threadIds);

    /** The ids of those messages, read before they are deleted: what has a translation to take with it (V51). */
    @Query("select m.id from ChatMessageEntity m where m.threadId in :threadIds")
    List<String> idsOfThreads(@Param("threadIds") List<String> threadIds);
}
