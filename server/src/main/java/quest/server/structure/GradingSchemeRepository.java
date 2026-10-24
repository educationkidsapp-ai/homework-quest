package quest.server.structure;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `grading_schemes` (feature 36, V58). Transactional and school-scoped, as {@link AcademyRepository} explains. */
@Transactional(readOnly = true)
public interface GradingSchemeRepository extends JpaRepository<Entities.GradingSchemeEntity, String> {
    Optional<Entities.GradingSchemeEntity> findBySchoolIdAndAcademyKey(String schoolId, String academyKey);
    List<Entities.GradingSchemeEntity> findBySchoolId(String schoolId);
}
