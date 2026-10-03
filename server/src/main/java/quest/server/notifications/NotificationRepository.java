package quest.server.notifications;

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
 * Every query starts from `userId` — the bell is one user's — and rides the `school` filter on top of that, so a
 * teacher of another school could not read a row even if she guessed its id. Transactional throughout for
 * `ChildRepository`'s reason: a derived or `@Query` method with a session of its own never reaches
 * {@link quest.server.tenancy.TenantTransactionManager}, and so would run with no filter at all.
 */
@Transactional(readOnly = true)
public interface NotificationRepository extends JpaRepository<Entities.NotificationEntity, String> {
    @Query("select n from NotificationEntity n where n.userId = :userId order by n.createdAt desc, n.id desc")
    List<Entities.NotificationEntity> newest(@Param("userId") String userId, Pageable page);

    @Query("select n from NotificationEntity n where n.userId = :userId and n.readAt is null order by n.createdAt desc, n.id desc")
    List<Entities.NotificationEntity> newestUnread(@Param("userId") String userId, Pageable page);

    @Query("select count(n) from NotificationEntity n where n.userId = :userId and n.readAt is null")
    int countUnread(@Param("userId") String userId);

    /** By id *and* owner: another user's id is simply not found, which is the 404 the contract promises. */
    @Query("select n from NotificationEntity n where n.id = :id and n.userId = :userId")
    Optional<Entities.NotificationEntity> findOwned(@Param("id") String id, @Param("userId") String userId);

    @Modifying @Transactional
    @Query("update NotificationEntity n set n.readAt = :at where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") String userId, @Param("at") Instant at);

    /**
     * T1's throttle: the one unread row this user already has about that entity, if any. `chat.message` writes at most
     * one unread row per thread per recipient — a second message she has not looked at yet updates the row rather than
     * adding to a bell she would then have to clear twice.
     */
    @Query("select n from NotificationEntity n where n.userId = :userId and n.kind = :kind and n.lessonId = :entityId"
            + " and n.readAt is null order by n.createdAt desc")
    List<Entities.NotificationEntity> unreadAbout(@Param("userId") String userId, @Param("kind") String kind, @Param("entityId") String entityId);

    /**
     * T1 (review): the upsert's first half — the unread row's new body and time, in one statement. `int` is the whole
     * answer: 0 means she has none unread and the caller inserts, 1 means the row was refreshed. No read, so two
     * senders racing on one thread cannot both decide to insert on the strength of a stale count.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true) @Transactional
    @Query("update NotificationEntity n set n.title = :title, n.body = :body, n.createdAt = :at"
            + " where n.userId = :userId and n.kind = :kind and n.lessonId = :entityId and n.readAt is null")
    int refreshUnread(@Param("userId") String userId, @Param("kind") String kind, @Param("entityId") String entityId,
                      @Param("title") String title, @Param("body") String body, @Param("at") Instant at);

    /** T1: reading the thread clears its bell entry, whichever message put it there. */
    @Modifying @Transactional
    @Query("update NotificationEntity n set n.readAt = :at where n.userId = :userId and n.kind = :kind"
            + " and n.lessonId = :entityId and n.readAt is null")
    int markAboutRead(@Param("userId") String userId, @Param("kind") String kind, @Param("entityId") String entityId, @Param("at") Instant at);

    /**
     * Every row about one entity, whoever it belongs to — RM2's weekly plan, replaced by a re-post. It is the one
     * query here that does not start from a `userId`, because the thing being withdrawn is the *subject* of the
     * notification rather than anyone's bell; the `school` filter still applies, as it does to every read above.
     */
    /** B3: who already has a row of this kind about this entity — a released exam is announced to a parent once. */
    @Query("select n from NotificationEntity n where n.kind = :kind and n.lessonId = :entityId")
    List<Entities.NotificationEntity> about(@Param("kind") String kind, @Param("entityId") String entityId);

    @Modifying @Transactional
    @Query("delete from NotificationEntity n where n.lessonId = :entityId")
    int deleteByEntity(@Param("entityId") String entityId);
}
