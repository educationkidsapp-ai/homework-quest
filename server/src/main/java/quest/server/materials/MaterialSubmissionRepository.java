package quest.server.materials;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the reason every repository of a tenant entity is (`TenantArchitectureTest`). */
@Transactional(readOnly = true)
public interface MaterialSubmissionRepository extends JpaRepository<Entities.MaterialSubmissionEntity, String> {
    List<Entities.MaterialSubmissionEntity> findBySchoolIdAndWeekStart(String schoolId, LocalDate weekStart);

    List<Entities.MaterialSubmissionEntity> findBySchoolIdAndTeacherIdAndWeekStart(String schoolId, String teacherId, LocalDate weekStart);

    Optional<Entities.MaterialSubmissionEntity> findOneBySchoolIdAndTeacherIdAndClassIdAndSubjectAndWeekStart(
            String schoolId, String teacherId, String classId, String subject, LocalDate weekStart);

    Optional<Entities.MaterialSubmissionEntity> findOneBySchoolIdAndId(String schoolId, String id);

    Optional<Entities.MaterialSubmissionEntity> findFirstBySchoolIdAndAttachmentId(String schoolId, String attachmentId);
}
