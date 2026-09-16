package quest.server.teacher;

import java.time.LocalDate;
import java.util.Collection;
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
public interface TeacherQuestionRepository extends JpaRepository<Entities.TeacherQuestionEntity, String> {
    List<Entities.TeacherQuestionEntity> findBySchoolIdAndTeacherIdOrderByCreatedAtDesc(String schoolId, String teacherId);
    List<Entities.TeacherQuestionEntity> findBySchoolIdOrderByCreatedAtDesc(String schoolId);

    /**
     * The sent questions whose window contains the day — the candidates for a child's "From your teacher" islands.
     * The class filter cannot be a column predicate (`class_ids_json` is a JSON list), so it is applied in
     * {@link TeacherQuestionService} over this already-narrow row set.
     */
    @Query("select q from TeacherQuestionEntity q where q.schoolId = :schoolId and q.sentAt is not null"
            + " and q.fromDate <= :day and q.toDate >= :day order by q.fromDate asc, q.createdAt asc")
    List<Entities.TeacherQuestionEntity> findActive(@Param("schoolId") String schoolId, @Param("day") LocalDate day);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query (see `ChildRepository.findOneById`). */
    @Query("select q from TeacherQuestionEntity q where q.id = :id")
    Optional<Entities.TeacherQuestionEntity> findOneById(@Param("id") String id);

    @Query("select q from TeacherQuestionEntity q where q.id in :ids")
    List<Entities.TeacherQuestionEntity> findAllByIdIn(@Param("ids") Collection<String> ids);
}
