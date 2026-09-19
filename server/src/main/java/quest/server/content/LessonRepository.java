package quest.server.content;

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
public interface LessonRepository extends JpaRepository<Entities.LessonEntity, String> {
    List<Entities.LessonEntity> findByCourseIdAndStatusAndDateBetweenOrderByDateAsc(String courseId, String status, LocalDate from, LocalDate to);
    List<Entities.LessonEntity> findByCourseIdAndStatus(String courseId, String status);
    List<Entities.LessonEntity> findAllByOrderByDateDescCreatedAtDesc();
    List<Entities.LessonEntity> findBySourceHash(String sourceHash);

    /**
     * Every lesson sitting in one of the transient statuses. Read by {@link quest.server.analysis.PipelineWatchdog}
     * only, which runs on the scheduler with no caller and therefore no school: a job left behind by a recycled
     * instance belongs to whichever school it belongs to, and the sweep has to be able to see all of them.
     */
    List<Entities.LessonEntity> findByStatusIn(Collection<String> statuses);

    /** Every published lesson of the given classes — the §2 map, assembled from Classes rather than from a Course. */
    List<Entities.LessonEntity> findByClassIdInAndStatusOrderByDateAsc(Collection<String> classIds, String status);

    /** One class's month, published or not: §6 screen 12's calendar shows what is planned as well as what is live. */
    List<Entities.LessonEntity> findByClassIdAndDateBetweenOrderByDateAsc(String classId, LocalDate from, LocalDate to);

    /**
     * Every lesson of a teacher's classes inside a window — the whole of `GET /teacher/week` and `GET /teacher/classes`
     * in one statement, however many assignments she holds (`TeacherWeekTest.the_week_costs_the_same_for_ten_assignments_as_for_one` pins it).
     */
    List<Entities.LessonEntity> findByClassIdInAndDateBetweenOrderByDateAsc(Collection<String> classIds, LocalDate from, LocalDate to);

    /**
     * The members of one lesson's lineage that already sit in a class: the original itself, or a copy of it (V8's
     * `copied_from_lesson_id`). This is what publish-to-siblings reuses instead of copying again — matching on day
     * and subject instead would publish an unrelated draft the class happened to be holding.
     */
    @Query("select l from LessonEntity l where l.classId = :classId and (l.id = :root or l.copiedFromLessonId = :root) order by l.createdAt asc")
    List<Entities.LessonEntity> findLineageIn(@Param("classId") String classId, @Param("root") String root);

    /**
     * Look a lesson up through a query, not `em.find`: Hibernate filters do not apply to `find`, so a `findById` would
     * hand a scoped caller a lesson of another school. Everything user-facing goes through here.
     */
    @Query("select l from LessonEntity l where l.id = :id")
    Optional<Entities.LessonEntity> findOneById(@Param("id") String id);
}
