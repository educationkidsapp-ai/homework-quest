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
    List<Entities.ChatThreadEntity> findByChildIdOrderByLastMessageAtDesc(String childId);
    List<Entities.ChatThreadEntity> findAllByOrderByLastMessageAtDesc();

    /** The teacher's list: unread first, then newest. */
    @Query("select t from ChatThreadEntity t where t.teacherId = :teacherId order by case when t.teacherUnread > 0 then 0 else 1 end, t.lastMessageAt desc")
    List<Entities.ChatThreadEntity> findForTeacher(@Param("teacherId") String teacherId);

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

    /** The child's hard delete (`RosterService.delete`): no foreign key cascades here, so her threads go by hand. */
    @Modifying @Transactional
    @Query("delete from ChatThreadEntity t where t.childId = :childId")
    int deleteByChild(@Param("childId") String childId);
}
