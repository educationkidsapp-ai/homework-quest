package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildMediaRepository extends JpaRepository<Entities.ChildMediaEntity, String> {
    List<Entities.ChildMediaEntity> findByChildId(String childId);

    /** Newest first: the retells and drawings on a child's timeline (§6 screen 15). */
    List<Entities.ChildMediaEntity> findByChildIdOrderByCreatedAtDesc(String childId);

    /**
     * Every saved retell and drawing of a whole class in one statement — the Results page links each open stop to
     * the work behind it, and a query per child would be an N+1 that only shows up in a real school (N4.1).
     */
    List<Entities.ChildMediaEntity> findByChildIdIn(java.util.Collection<String> childIds);
}
