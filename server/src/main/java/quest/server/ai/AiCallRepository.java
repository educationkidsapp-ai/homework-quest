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
}
