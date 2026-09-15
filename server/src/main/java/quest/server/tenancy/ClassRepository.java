package quest.server.tenancy;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every method is transactional so that the `school` Hibernate filter is on for all of them: Spring Data makes only
 * the `CrudRepository` methods transactional by default, while derived and `@Query` methods run with a session of
 * their own and would otherwise never reach {@link quest.server.tenancy.TenantTransactionManager}. Writes keep their
 * own read-write attribute from `SimpleJpaRepository`. `TenantArchitectureTest` fails if a repository of a tenant
 * entity loses this annotation.
 */
@Transactional(readOnly = true)
public interface ClassRepository extends JpaRepository<Entities.ClassEntity, String> {
    List<Entities.ClassEntity> findBySchoolId(String schoolId);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGrade(String schoolId, String curriculum, int grade);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGradeAndSubject(String schoolId, String curriculum, int grade, String subject);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndSubjectOrderByCreatedAtAsc(String schoolId, String curriculum, int grade, String subject);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndSubjectAndTeacherIdOrderByCreatedAtAsc(String schoolId, String curriculum, int grade, String subject, String teacherId);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ChildRepository.findOneById`). */
    @Query("select k from ClassEntity k where k.id = :id")
    Optional<Entities.ClassEntity> findOneById(@Param("id") String id);
}
