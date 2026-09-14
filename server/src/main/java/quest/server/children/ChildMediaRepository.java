package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildMediaRepository extends JpaRepository<Entities.ChildMediaEntity, String> {
    List<Entities.ChildMediaEntity> findByChildId(String childId);
}
