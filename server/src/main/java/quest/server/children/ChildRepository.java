package quest.server.children;

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
public interface ChildRepository extends JpaRepository<Entities.ChildEntity, String> {
    List<Entities.ChildEntity> findByParentIdAndDeletedAtIsNullOrderByCreatedAt(String parentId);
    /** Every live child the caller may see — scoped by the `school` filter, so it is one school's roll. */
    List<Entities.ChildEntity> findByDeletedAtIsNullOrderByCreatedAt();
    long countByCurriculumAndGradeAndDeletedAtIsNull(String curriculum, int grade);

    /**
     * `[curriculum, grade, live children]` for every course at once — one query instead of one per course, and scoped
     * by the `school` filter like every other read here.
     */
    @Query("select c.curriculum, c.grade, count(c) from ChildEntity c where c.deletedAt is null group by c.curriculum, c.grade")
    List<Object[]> countByCourse();

    /**
     * Look a child up through a query, not `em.find`: Hibernate filters do not apply to `find`, so a `findById` would
     * hand a scoped caller a child of another school — and with her every attempt, completion, sticker and recording,
     * which are all reached by `child_id`.
     */
    @Query("select c from ChildEntity c where c.id = :id")
    Optional<Entities.ChildEntity> findOneById(@Param("id") String id);
}
