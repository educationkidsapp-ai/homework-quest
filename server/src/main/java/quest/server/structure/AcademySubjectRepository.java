package quest.server.structure;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** `academy_subjects` (feature 36, V58). Transactional and school-scoped, as {@link AcademyRepository} explains. */
@Transactional(readOnly = true)
public interface AcademySubjectRepository extends JpaRepository<Entities.AcademySubjectEntity, String> {
    List<Entities.AcademySubjectEntity> findBySchoolIdOrderByAcademyKeyAscPositionAscSubjectKeyAsc(String schoolId);
    List<Entities.AcademySubjectEntity> findBySchoolIdAndAcademyKeyOrderByPositionAscSubjectKeyAsc(String schoolId, String academyKey);
}
