package quest.server.children;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StickerRepository extends JpaRepository<Entities.StickerEntity, String> {
    List<Entities.StickerEntity> findByChildIdOrderByEarnedAt(String childId);
}
