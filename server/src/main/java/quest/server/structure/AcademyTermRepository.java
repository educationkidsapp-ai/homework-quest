package quest.server.structure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `academy_terms` (feature 36, V58). Transactional and school-scoped, as {@link AcademyRepository} explains. */
@Transactional(readOnly = true)
public interface AcademyTermRepository extends JpaRepository<Entities.AcademyTermEntity, String> {
    List<Entities.AcademyTermEntity> findBySchoolIdAndAcademyKeyOrderBySchoolYearAscStartsOnAsc(String schoolId, String academyKey);
    List<Entities.AcademyTermEntity> findBySchoolIdOrderByAcademyKeyAscSchoolYearAscStartsOnAsc(String schoolId);
}
