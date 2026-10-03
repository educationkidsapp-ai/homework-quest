package quest.server.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason `ChildRepository` gives: the `school` filter is enabled per transaction, and Spring
 * Data makes only the `CrudRepository` methods transactional on their own. The counter updates carry their own
 * read-write attribute, because a `readOnly` transaction would refuse them on PostgreSQL.
 */
@Transactional(readOnly = true)
public interface ChatThreadRepository extends JpaRepository<Entities.ChatThreadEntity, String> {
    /**
     * The parent's <em>Messages</em> thread with one staff member — the (child, staff, `''`) row V34's unique index
     * keeps single. A complaint (B6) is never this row, whoever it is addressed to.
     */
    @Query("select t from ChatThreadEntity t where t.childId = :childId and t.teacherId = :teacherId and t.threadKey = ''")
    Optional<Entities.ChatThreadEntity> findByChildIdAndTeacherId(@Param("childId") String childId, @Param("teacherId") String teacherId);
    Optional<Entities.ChatThreadEntity> findByTeacherIdAndPeerUserId(String teacherId, String peerUserId);
    /** Every thread of the child, complaints included — what her hard delete and presence read. */
    List<Entities.ChatThreadEntity> findByChildIdOrderByLastMessageAtDesc(String childId);

    /** B6: the child's Messages threads — her complaints are not among them. */
    @Query("select t from ChatThreadEntity t where t.childId = :childId and t.topic <> 'complaint' order by t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findMessageThreadsOf(@Param("childId") String childId);

    /** The Admin's support list: every Messages thread of the school, newest first (B6: no complaint). */
    @Query("select t from ChatThreadEntity t where t.topic <> 'complaint' order by t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findMessageThreads();

    /** B6: every complaint of the school (the `school` filter), newest activity first. */
    @Query("select t from ChatThreadEntity t where t.topic = 'complaint' order by t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findComplaints();

    /** B6: the complaints about one child — her parent's Complaints page. */
    @Query("select t from ChatThreadEntity t where t.childId = :childId and t.topic = 'complaint' order by t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findComplaintsOf(@Param("childId") String childId);

    /** B6: the complaints addressed to one staff member. */
    @Query("select t from ChatThreadEntity t where t.teacherId = :staffId and t.topic = 'complaint' order by t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findComplaintsTo(@Param("staffId") String staffId);

    /** The teacher's list: unread first, then newest. B6: Messages only. */
    @Query("select t from ChatThreadEntity t where t.teacherId = :teacherId and t.topic <> 'complaint' order by case when t.teacherUnread > 0 then 0 else 1 end, t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findForTeacher(@Param("teacherId") String teacherId);

    /**
     * R4: every thread whose staff peer is this person — the parents who wrote to her and the manager she reports to
     * — unread first then newest, the order {@link #findForTeacher} gives a teacher. `staff_role` is not named: a
     * coordinator's id never appears on a TEACHER row, so filtering by it would only hide a mis-seeded row. B6: Messages
     * only — a complaint addressed to her is on her Complaints page.
     */
    @Query("select t from ChatThreadEntity t where (t.teacherId = :staffId or t.peerUserId = :staffId) and t.topic <> 'complaint'"
            + " order by case when t.teacherUnread > 0 or t.parentUnread > 0 then 0 else 1 end, t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findForStaff(@Param("staffId") String staffId);

    /**
     * B5b review: the thread row, locked until the send's transaction ends — so two sends naming one `clientId` in one
     * thread run one after the other, and the second finds the first's row instead of racing it to the unique index.
     */
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChatThreadEntity t where t.id = :id")
    Optional<Entities.ChatThreadEntity> lockById(@Param("id") String id);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select t from ChatThreadEntity t where t.id = :id")
    Optional<Entities.ChatThreadEntity> findOneById(@Param("id") String id);

    @Modifying @Transactional
    @Query("update ChatThreadEntity t set t.teacherUnread = t.teacherUnread + 1, t.lastMessageAt = :at where t.id = :id")
    int bumpTeacherUnread(@Param("id") String id, @Param("at") Instant at);

    @Modifying @Transactional
    @Query("update ChatThreadEntity t set t.parentUnread = t.parentUnread + 1, t.lastMessageAt = :at where t.id = :id")
    int bumpParentUnread(@Param("id") String id, @Param("at") Instant at);

    @Modifying @Transactional
    @Query("update ChatThreadEntity t set t.teacherUnread = 0 where t.id = :id")
    int clearTeacherUnread(@Param("id") String id);

    @Modifying @Transactional
    @Query("update ChatThreadEntity t set t.parentUnread = 0 where t.id = :id")
    int clearParentUnread(@Param("id") String id);

    /**
     * B6: a complaint's status move as one conditional statement — it changes the row only when the status is not
     * already {@code status}, and answers how many rows it changed (0 or 1). The row lock serialises concurrent moves,
     * so of two identical ones exactly one answers 1. It writes the status columns and nothing else (never the unread
     * counters a concurrent message bumps), and clears the persistence context so the caller re-reads the row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true) @Transactional
    @Query("update ChatThreadEntity t set t.status = :status, t.resolvedAt = :at, t.resolvedBy = :by"
            + " where t.id = :id and t.topic = 'complaint' and t.status <> :status")
    int moveComplaint(@Param("id") String id, @Param("status") String status, @Param("at") Instant at, @Param("by") String by);

    /** The child's hard delete (`RosterService.delete`): no foreign key cascades here, so her threads go by hand. */
    @Modifying @Transactional
    @Query("delete from ChatThreadEntity t where t.childId = :childId")
    int deleteByChild(@Param("childId") String childId);
}
