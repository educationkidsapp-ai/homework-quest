package quest.server.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import quest.server.ai.Entities.AiCallEntity;

/**
 * The ledger. Every read is scoped by the `school` filter and so by the caller's own school — `TenantArchitectureTest`
 * is why the interface is {@code @Transactional}: the filter is enabled per transaction, and a derived query that
 * opened its own would run with it off.
 */
@Transactional
public interface AiCallRepository extends JpaRepository<AiCallEntity, String> {
    List<AiCallEntity> findByFeatureOrderByCreatedAtAsc(String feature);
    List<AiCallEntity> findByTagOrderByCreatedAtAsc(String tag);

    /**
     * How many calls one feature made under one tag since an instant, for this school. Feature 29's per-reader
     * ceiling counts with it: an in-memory window is per instance, and a ledger row is written by whichever
     * instance made the call ({@link quest.server.translate.TranslationRateLimiter}). The school is named as well as
     * filtered, because a parent's request carries no tenant scope — `TranslationRepository.hit`'s rule.
     */
    @org.springframework.data.jpa.repository.Query("select count(c) from AiCallEntity c"
            + " where c.schoolId = :schoolId and c.feature = :feature and c.tag = :tag and c.createdAt >= :since")
    long countSince(@org.springframework.data.repository.query.Param("schoolId") String schoolId,
                    @org.springframework.data.repository.query.Param("feature") String feature,
                    @org.springframework.data.repository.query.Param("tag") String tag,
                    @org.springframework.data.repository.query.Param("since") java.time.Instant since);
}
