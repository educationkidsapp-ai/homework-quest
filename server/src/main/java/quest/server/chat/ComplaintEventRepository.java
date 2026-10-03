package quest.server.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** V34 (B6): a complaint's status history, oldest first. Transactional for `ChildRepository`'s reason (the filter). */
@Transactional(readOnly = true)
public interface ComplaintEventRepository extends JpaRepository<Entities.ComplaintEventEntity, String> {
    List<Entities.ComplaintEventEntity> findByThreadIdOrderByChangedAtAscIdAsc(String threadId);

    /** The child's hard delete (`RosterService.delete`) takes her complaints' histories with her threads. */
    @Modifying @Transactional
    @Query("delete from ComplaintEventEntity e where e.threadId in :threadIds")
    int deleteByThreads(@Param("threadIds") List<String> threadIds);
}
