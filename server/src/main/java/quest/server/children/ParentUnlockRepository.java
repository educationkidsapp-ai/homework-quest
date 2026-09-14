package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParentUnlockRepository extends JpaRepository<Entities.ParentUnlockEntity, Entities.ParentUnlockId> {
    List<Entities.ParentUnlockEntity> findByChildId(String childId);
}
