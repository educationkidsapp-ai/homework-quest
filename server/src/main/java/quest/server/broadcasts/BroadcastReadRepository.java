package quest.server.broadcasts;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** The read marks of one reader, and the marks of a weekly plan a re-post replaced. {@link BroadcastRepository} says why it is transactional. */
@Transactional(readOnly = true)
public interface BroadcastReadRepository extends JpaRepository<Entities.BroadcastReadEntity, String> {
    /** Which of these broadcasts this reader has opened — one statement per feed, never one per row. */
    @Query("select r.broadcastId from BroadcastReadEntity r where r.readerId = :readerId and r.broadcastId in :ids")
    List<String> readBy(@Param("readerId") String readerId, @Param("ids") List<String> ids);

    /**
     * MG1: `[broadcastId, how many people have opened it]` for a whole archive page in one statement — the manager's
     * archive says how many read each plan, and one count per row would be a statement per week.
     */
    @Query("select r.broadcastId, count(r.id) from BroadcastReadEntity r where r.broadcastId in :ids group by r.broadcastId")
    List<Object[]> countsBy(@Param("ids") List<String> ids);

    @Query("select r from BroadcastReadEntity r where r.broadcastId = :broadcastId and r.readerId = :readerId")
    Optional<Entities.BroadcastReadEntity> findOne(@Param("broadcastId") String broadcastId, @Param("readerId") String readerId);

    @Modifying @Transactional
    @Query("delete from BroadcastReadEntity r where r.broadcastId = :broadcastId")
    int deleteByBroadcast(@Param("broadcastId") String broadcastId);
}
