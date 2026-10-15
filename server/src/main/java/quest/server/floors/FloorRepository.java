package quest.server.floors;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * `floors` (feature 20). Transactional for the reason every other tenant repository is: Spring Data only wraps the
 * `CrudRepository` methods and the `school` filter is enabled per transaction
 * ({@link quest.server.tenancy.TenantTransactionManager}), so a derived query outside one would run unfiltered.
 *
 * <p>Every method names the school as well, so a floor of another school is invisible twice over — and because
 * feature 16's job and feature 14's {@code DirectManagerResolver} run on the scheduler's thread, which has no tenant
 * scope at all and therefore no filter.
 */
@Transactional(readOnly = true)
public interface FloorRepository extends JpaRepository<Entities.FloorEntity, String> {
    List<Entities.FloorEntity> findBySchoolIdOrderByNameAsc(String schoolId);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (as `ClassRepository` does). */
    @Query("select f from FloorEntity f where f.id = :id")
    Optional<Entities.FloorEntity> findOneById(@Param("id") String id);

    List<Entities.FloorEntity> findBySchoolIdAndIdInOrderByNameAsc(String schoolId, List<String> ids);
}
