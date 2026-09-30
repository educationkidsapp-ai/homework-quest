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
     * A page of the children on a set of sections, matched by name or by either address the school holds — the
     * roster's own `parent_email` and the account her parent signed up with, which is why `ParentEntity` appears in an
     * `exists` rather than a join: `children` has no association to `parents`, and the page's addresses are still
     * fetched by id afterwards because at most a hundred rows come back.
     *
     * <p>`q` is always a `like` pattern (`%` when nothing was asked for), so the statement carries no `is null` test
     * a driver would have to type, and `escape` is what makes a search box literal: {@code 100%} is four characters to
     * look for, not "anything at all". {@code PeopleDirectoryService.pattern} is the one place that escapes `%`, `_`
     * and the escape character itself, and all three statements here name the same one.
     *
     * <p>The escape character is the backslash, declared on every one of the three `like`s rather than left to the
     * dialect's default: `PostgresStaffAttendanceTest` runs this statement on PostgreSQL 16, because an escape clause
     * that works on H2 and not on the database QA runs on would widen a search silently.
     */
    @Query("select c from ChildEntity c where c.classId in :classIds and c.deletedAt is null and ("
            + "lower(c.name) like :q escape '\\' or lower(coalesce(c.parentEmail, '')) like :q escape '\\'"
            + " or exists (select 1 from ParentEntity p where p.id = c.parentId and lower(p.email) like :q escape '\\'))")
    List<Entities.ChildEntity> findDirectory(@Param("classIds") java.util.Collection<String> classIds,
                                            @Param("q") String q, org.springframework.data.domain.Pageable page);

    /** How many children that same filter matches, for the directory's `total` — the same predicate, counted. */
    @Query("select count(c) from ChildEntity c where c.classId in :classIds and c.deletedAt is null and ("
            + "lower(c.name) like :q escape '\\' or lower(coalesce(c.parentEmail, '')) like :q escape '\\'"
            + " or exists (select 1 from ParentEntity p where p.id = c.parentId and lower(p.email) like :q escape '\\'))")
    long countDirectory(@Param("classIds") java.util.Collection<String> classIds, @Param("q") String q);

    // -------------------------------------------------------------- the Admin's Children & parents page (MA1)

    /**
     * A page of the school's children with everything the owner's item 5 prints, matched on the child's name, either
     * address the school holds, or the parent's own name and telephone number. The school is named explicitly *and*
     * the `school` filter is on, as {@link #findBySchoolIdAndDeletedAtIsNullOrderByNameAsc} names it: the value comes
     * from {@link quest.server.tenancy.TenantContext}, never from a request parameter.
     *
     * <p>The `escape '\'` on every `like` is what makes the search box literal text — `100%` is four characters to
     * look for, not "anything at all" — and `PeopleDirectoryService.pattern` is the one place that escapes `%`, `_`
     * and the backslash itself. The telephone number is matched without one because the stored form holds no
     * wildcard: it is `+` and digits ({@link quest.server.platform.Phones}).
     */
    @Query("select c from ChildEntity c where c.schoolId = :schoolId and c.deletedAt is null and ("
            + "lower(c.name) like :q escape '\\' or lower(coalesce(c.parentEmail, '')) like :q escape '\\'"
            + " or exists (select 1 from ParentEntity p where p.id = c.parentId and ("
            + "lower(p.email) like :q escape '\\' or lower(coalesce(p.displayName, '')) like :q escape '\\'"
            + " or coalesce(p.phone, '') like :q)))")
    List<Entities.ChildEntity> findSchoolDirectory(@Param("schoolId") String schoolId, @Param("q") String q,
                                                  org.springframework.data.domain.Pageable page);

    /** How many rows that same filter matches, for the page's `total` — the same predicate, counted. */
    @Query("select count(c) from ChildEntity c where c.schoolId = :schoolId and c.deletedAt is null and ("
            + "lower(c.name) like :q escape '\\' or lower(coalesce(c.parentEmail, '')) like :q escape '\\'"
            + " or exists (select 1 from ParentEntity p where p.id = c.parentId and ("
            + "lower(p.email) like :q escape '\\' or lower(coalesce(p.displayName, '')) like :q escape '\\'"
            + " or coalesce(p.phone, '') like :q)))")
    long countSchoolDirectory(@Param("schoolId") String schoolId, @Param("q") String q);

    /**
     * Which schools this parent already has a live child in. Native on purpose: a Hibernate filter does not touch a
     * native statement, and the question only has an answer when it is asked across every school — reusing an address
     * that belongs to another school's family is what `POST /admin/children` refuses with a 409.
     */
    @Query(value = "SELECT DISTINCT school_id FROM children WHERE parent_id = :parentId AND deleted_at IS NULL", nativeQuery = true)
    List<String> findSchoolIdsOfParentAcrossSchools(@Param("parentId") String parentId);

    /**
     * Look a child up through a query, not `em.find`: Hibernate filters do not apply to `find`, so a `findById` would
     * hand a scoped caller a child of another school — and with her every attempt, completion, sticker and recording,
     * which are all reached by `child_id`.
     */
    @Query("select c from ChildEntity c where c.id = :id")
    Optional<Entities.ChildEntity> findOneById(@Param("id") String id);
}
