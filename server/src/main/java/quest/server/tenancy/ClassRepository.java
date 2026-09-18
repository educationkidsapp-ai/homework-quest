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
    List<Entities.ClassEntity> findBySchoolIdOrderByCurriculumAscGradeAscSubjectAsc(String schoolId);
    /** The classes one teacher owns — her Home (§6 screen 11) and the Teachers tab (§6 screen 20). */
    List<Entities.ClassEntity> findBySchoolIdAndTeacherIdOrderByCurriculumAscGradeAscSubjectAsc(String schoolId, String teacherId);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGrade(String schoolId, String curriculum, int grade);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGradeAndSubject(String schoolId, String curriculum, int grade, String subject);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndSubjectOrderByCreatedAtAsc(String schoolId, String curriculum, int grade, String subject);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndSubjectAndTeacherIdOrderByCreatedAtAsc(String schoolId, String curriculum, int grade, String subject, String teacherId);

    // -------------------------------------------------------------- sections (V7)

    /** The school's sections, newest schema only: a pre-V7 leftover has no `name` and is never listed. */
    List<Entities.ClassEntity> findBySchoolIdAndNameIsNotNullOrderByCurriculumAscGradeAscNameAsc(String schoolId);
    List<Entities.ClassEntity> findBySchoolIdAndCurriculumAndGradeAndNameIsNotNullOrderByNameAsc(String schoolId, String curriculum, int grade);
    Optional<Entities.ClassEntity> findFirstBySchoolIdAndCurriculumAndGradeAndNameIgnoreCase(String schoolId, String curriculum, int grade, String name);

    /**
     * The public join-code lookup, and the only query here that names no school: a parent types a code before she
     * belongs to anything, so the row is found across tenants and answers 404 unless it is an active section of an
     * active school whose code is still enabled. `@Query` rather than a derived name so the filter, which is off for
     * an unauthenticated request anyway, is not what the rule depends on.
     */
    @Query("select k from ClassEntity k where upper(k.joinCode) = upper(:code) and k.name is not null and k.active = true and k.joinCodeEnabled = true")
    Optional<Entities.ClassEntity> findByJoinCode(@Param("code") String code);

    boolean existsByJoinCodeIgnoreCase(String joinCode);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ChildRepository.findOneById`). */
    @Query("select k from ClassEntity k where k.id = :id")
    Optional<Entities.ClassEntity> findOneById(@Param("id") String id);
}
