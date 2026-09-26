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
    long countByCurriculumAndGradeAndDeletedAtIsNull(String curriculum, int grade);

    /**
     * The live children of a school. `school_id` is named explicitly because the parent side of these features
     * carries no tenant scope at all. A <em>class's</em> children are read by `class_id` below, never by the
     * class's grade: `1A` and `1B` are two sections of one grade and one is never the other's roster (N2.3b).
     */
    List<Entities.ChildEntity> findBySchoolIdAndDeletedAtIsNullOrderByNameAsc(String schoolId);

    // -------------------------------------------------------------- rosters (V7)

    /** One section's roster, retired rows included so the Admin screen can show and re-activate them. */
    List<Entities.ChildEntity> findByClassIdAndDeletedAtIsNullOrderByNameAsc(String classId);

    /**
     * How many children one class's register holds — the same roster the Results page counts, without loading it.
     * The Exams tab wants the denominator of "12 of 24 sat it" and nothing else about them.
     */
    long countByClassIdAndDeletedAtIsNull(String classId);
    List<Entities.ChildEntity> findByClassIdAndActiveTrueAndDeletedAtIsNullOrderByNameAsc(String classId);

    /** `[classId, live children]` for a page of sections at once — one statement rather than one per class. */
    @Query("select c.classId, count(c) from ChildEntity c where c.classId in :classIds and c.active = true and c.deletedAt is null group by c.classId")
    List<Object[]> countByClassIdIn(@Param("classIds") List<String> classIds);

    /**
     * `[curriculum, grade, live children]` for every course at once — one query instead of one per course, and scoped
     * by the `school` filter like every other read here.
     */
    @Query("select c.curriculum, c.grade, count(c) from ChildEntity c where c.deletedAt is null group by c.curriculum, c.grade")
    List<Object[]> countByCourse();

    // -------------------------------------------------------------- the department directory (RM5)

    /**
     * A page of the children on a set of sections, matched by name or by the roster's own parent address: what
     * `GET /management/people/children` answers. `q` is always a `like` pattern (`%` when nothing was asked for), so
     * the statement carries no `is null` test a driver would have to type, and the registered parent's address is
     * resolved from `parents` afterwards rather than joined here — a page is at most a hundred rows and one `in` query
     * is cheaper than a join across a table this one has no association to.
     */
    @Query("select c from ChildEntity c where c.classId in :classIds and c.deletedAt is null"
            + " and (lower(c.name) like :q or lower(coalesce(c.parentEmail, '')) like :q)")
    List<Entities.ChildEntity> findDirectory(@Param("classIds") java.util.Collection<String> classIds,
                                            @Param("q") String q, org.springframework.data.domain.Pageable page);

    /** How many children that same filter matches, for the directory's `total`. */
    @Query("select count(c) from ChildEntity c where c.classId in :classIds and c.deletedAt is null"
            + " and (lower(c.name) like :q or lower(coalesce(c.parentEmail, '')) like :q)")
    long countDirectory(@Param("classIds") java.util.Collection<String> classIds, @Param("q") String q);

    /**
     * Look a child up through a query, not `em.find`: Hibernate filters do not apply to `find`, so a `findById` would
     * hand a scoped caller a child of another school — and with her every attempt, completion, sticker and recording,
     * which are all reached by `child_id`.
     */
    @Query("select c from ChildEntity c where c.id = :id")
    Optional<Entities.ChildEntity> findOneById(@Param("id") String id);
}
