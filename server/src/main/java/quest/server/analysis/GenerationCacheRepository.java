package quest.server.analysis;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationCacheRepository extends JpaRepository<CacheEntities.GenerationCacheEntity, String> {
    List<CacheEntities.GenerationCacheEntity> findBySourceHash(String sourceHash);

    /** `[source hash, tokens spent on its generations]` for a set of hashes — one query for a whole cache listing. */
    @org.springframework.data.jpa.repository.Query("select g.sourceHash, sum(g.tokenUsage) from GenerationCacheEntity g where g.sourceHash in :hashes group by g.sourceHash")
    List<Object[]> sumTokensBySourceHash(@org.springframework.data.repository.query.Param("hashes") Collection<String> hashes);
}
