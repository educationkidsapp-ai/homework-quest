package quest.server.materials;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is: a derived query outside a transaction never
 * reaches {@link quest.server.tenancy.TenantTransactionManager}, where the `school` filter is switched on
 * (`TenantArchitectureTest`). Every query also names the school, because the reminder job has no request to take one
 * from.
 */
@Transactional(readOnly = true)
public interface MaterialSettingsRepository extends JpaRepository<Entities.MaterialSettingsEntity, String> {
    Optional<Entities.MaterialSettingsEntity> findOneBySchoolId(String schoolId);
}
