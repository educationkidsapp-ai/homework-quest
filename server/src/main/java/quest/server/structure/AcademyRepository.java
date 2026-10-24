package quest.server.structure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * `academies` (feature 36, V58). Transactional for the reason every other tenant repository is: Spring Data wraps
 * only the `CrudRepository` methods and the `school` filter is enabled per transaction
 * ({@link quest.server.tenancy.TenantTransactionManager}), so a derived query outside one would run unfiltered.
 *
 * <p>Every method names the school as well, so another school's academy is invisible twice over — and because
 * {@link GradingSchemes} is read from the gradebook, the exports and the exam results, some of which run with no
 * tenant scope at all.
 */
@Transactional(readOnly = true)
public interface AcademyRepository extends JpaRepository<Entities.AcademyEntity, String> {
    List<Entities.AcademyEntity> findBySchoolIdOrderByAcademyKeyAsc(String schoolId);
    Optional<Entities.AcademyEntity> findBySchoolIdAndAcademyKey(String schoolId, String academyKey);
}
