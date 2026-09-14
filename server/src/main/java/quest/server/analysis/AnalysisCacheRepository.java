package quest.server.analysis;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisCacheRepository extends JpaRepository<CacheEntities.AnalysisCacheEntity, String> {
    List<CacheEntities.AnalysisCacheEntity> findAllByOrderByCreatedAtDesc();
}
