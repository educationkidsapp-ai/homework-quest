package quest.server.flags;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** The flag definitions (§4). Not a tenant table: the same 14 keys exist for every school. */
public interface FeatureFlagRepository extends JpaRepository<Entities.FeatureFlagEntity, String> {
    List<Entities.FeatureFlagEntity> findAllByOrderByKeyAsc();
}
