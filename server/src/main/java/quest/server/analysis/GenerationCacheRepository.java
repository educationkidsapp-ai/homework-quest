package quest.server.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationCacheRepository extends JpaRepository<CacheEntities.GenerationCacheEntity, String> {
    List<CacheEntities.GenerationCacheEntity> findBySourceHash(String sourceHash);
}
