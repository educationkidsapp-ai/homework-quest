package quest.server.structure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `grading_bands` (feature 36, V58). Transactional and school-scoped, as {@link AcademyRepository} explains. */
@Transactional(readOnly = true)
public interface GradingBandRepository extends JpaRepository<Entities.GradingBandEntity, String> {
    List<Entities.GradingBandEntity> findBySchoolIdAndSchemeIdOrderByLowerBoundAsc(String schoolId, String schemeId);
    List<Entities.GradingBandEntity> findBySchoolId(String schoolId);
}
