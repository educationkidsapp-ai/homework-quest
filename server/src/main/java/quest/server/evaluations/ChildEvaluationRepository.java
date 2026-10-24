package quest.server.evaluations;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). Every staff query
 * names the school; {@link #findByChildIdAndStateOrderBySubjectAsc} is the parent's and names the child instead,
 * because she carries no tenant scope and the child in the path has already been proved hers — the same shape
 * {@link quest.server.tenancy.TeacherScope#assignmentsOn} has for her side of feature 32.
 */
@Transactional(readOnly = true)
public interface ChildEvaluationRepository extends JpaRepository<Entities.ChildEvaluationEntity, String> {
    List<Entities.ChildEvaluationEntity> findBySchoolIdAndRoundId(String schoolId, String roundId);

    List<Entities.ChildEvaluationEntity> findBySchoolIdAndRoundIdAndTeacherId(String schoolId, String roundId, String teacherId);

    Optional<Entities.ChildEvaluationEntity> findOneBySchoolIdAndId(String schoolId, String id);

    Optional<Entities.ChildEvaluationEntity> findOneBySchoolIdAndRoundIdAndTeacherIdAndClassIdAndSubjectAndChildId(
            String schoolId, String roundId, String teacherId, String classId, String subject, String childId);

    /** The parent's own read: one child's rows in one state, whatever the round — the rounds are filtered after. */
    List<Entities.ChildEvaluationEntity> findByChildIdAndStateOrderBySubjectAsc(String childId, String state);
}
