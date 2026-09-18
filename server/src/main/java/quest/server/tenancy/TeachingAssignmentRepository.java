package quest.server.tenancy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who teaches what (`docs/teacher-flow.md` §2). Transactional for the same reason every other tenant repository is:
 * Spring Data only wraps the `CrudRepository` methods, and the `school` filter is enabled per transaction
 * ({@link TenantTransactionManager}). `TenantArchitectureTest` fails if the annotation is lost.
 */
@Transactional(readOnly = true)
public interface TeachingAssignmentRepository extends JpaRepository<Entities.TeachingAssignmentEntity, String> {
    List<Entities.TeachingAssignmentEntity> findBySchoolIdOrderByClassIdAscSubjectAsc(String schoolId);
    List<Entities.TeachingAssignmentEntity> findBySchoolIdAndTeacherIdOrderByClassIdAscSubjectAsc(String schoolId, String teacherId);
    List<Entities.TeachingAssignmentEntity> findByClassIdOrderBySubjectAsc(String classId);

    /** Every assignment of a page of classes at once — one statement, whatever the number of classes. */
    List<Entities.TeachingAssignmentEntity> findByClassIdInOrderByClassIdAscSubjectAsc(List<String> classIds);
    List<Entities.TeachingAssignmentEntity> findByTeacherIdInOrderByClassIdAscSubjectAsc(List<String> teacherIds);

    Optional<Entities.TeachingAssignmentEntity> findByClassIdAndSubject(String classId, String subject);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ClassRepository.findOneById`). */
    @Query("select a from TeachingAssignmentEntity a where a.id = :id")
    Optional<Entities.TeachingAssignmentEntity> findOneById(@Param("id") String id);
}
