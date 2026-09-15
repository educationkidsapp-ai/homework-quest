package quest.server.tenancy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassRepository extends JpaRepository<Entities.ClassEntity, String> {
    List<Entities.ClassEntity> findBySchoolId(String schoolId);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGradeAndSubject(String schoolId, String curriculum, int grade, String subject);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndSubjectOrderByCreatedAtAsc(String schoolId, String curriculum, int grade, String subject);
}
