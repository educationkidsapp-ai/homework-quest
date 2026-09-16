package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildMediaRepository extends JpaRepository<Entities.ChildMediaEntity, String> {
    List<Entities.ChildMediaEntity> findByChildId(String childId);

    /** Newest first: the retells and drawings on a child's timeline (§6 screen 15). */
    List<Entities.ChildMediaEntity> findByChildIdOrderByCreatedAtDesc(String childId);
}
