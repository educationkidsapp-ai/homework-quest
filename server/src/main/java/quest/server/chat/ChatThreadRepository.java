package quest.server.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
    Optional<Entities.ChatThreadEntity> findByChildIdAndTeacherId(String childId, String teacherId);
    Optional<Entities.ChatThreadEntity> findByTeacherIdAndPeerUserId(String teacherId, String peerUserId);
    List<Entities.ChatThreadEntity> findByChildIdOrderByLastMessageAtDesc(String childId);
    List<Entities.ChatThreadEntity> findAllByOrderByLastMessageAtDesc();

    /** The teacher's list: unread first, then newest. */
    @Query("select t from ChatThreadEntity t where t.teacherId = :teacherId order by case when t.teacherUnread > 0 then 0 else 1 end, t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findForTeacher(@Param("teacherId") String teacherId);

    /**
     * R4: every thread whose staff peer is this person — the parents who wrote to her and the manager she reports to
     * — unread first then newest, the order {@link #findForTeacher} gives a teacher. `staff_role` is not named: a
     * coordinator's id never appears on a TEACHER row, so filtering by it would only hide a mis-seeded row.
     */
    @Query("select t from ChatThreadEntity t where t.teacherId = :staffId or t.peerUserId = :staffId"
            + " order by case when t.teacherUnread > 0 or t.parentUnread > 0 then 0 else 1 end, t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findForStaff(@Param("staffId") String staffId);

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

    /** R4: `open` / `resolved` on a complaint, written as one row update like the two unread counters are. */
    @Modifying @Transactional
    @Query("update ChatThreadEntity t set t.status = :status, t.resolvedAt = :at, t.resolvedBy = :by where t.id = :id")
    int setStatus(@Param("id") String id, @Param("status") String status, @Param("at") Instant at, @Param("by") String by);

    /** The child's hard delete (`RosterService.delete`): no foreign key cascades here, so her threads go by hand. */
    @Modifying @Transactional
    @Query("delete from ChatThreadEntity t where t.childId = :childId")
    int deleteByChild(@Param("childId") String childId);
}
