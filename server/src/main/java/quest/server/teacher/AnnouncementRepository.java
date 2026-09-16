package quest.server.teacher;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Transactional for the same reason as every other tenant repository (see {@link TeacherQuestionRepository}). */
@Transactional(readOnly = true)
public interface AnnouncementRepository extends JpaRepository<Entities.AnnouncementEntity, String> {
    List<Entities.AnnouncementEntity> findBySchoolIdAndTeacherIdOrderByPublishedAtDesc(String schoolId, String teacherId);
    List<Entities.AnnouncementEntity> findBySchoolIdOrderByPublishedAtDesc(String schoolId);

    /**
     * The live announcements of the classes a child belongs to: published, not yet expired, in one statement whatever
     * the number of classes. Named `schoolId` explicitly because the parent side runs with no tenant scope at all.
     */
    @Query("select a from AnnouncementEntity a where a.schoolId = :schoolId and a.classId in :classIds"
            + " and a.publishedAt <= :now and (a.expiresAt is null or a.expiresAt > :now) order by a.publishedAt desc")
    List<Entities.AnnouncementEntity> findLive(@Param("schoolId") String schoolId, @Param("classIds") Collection<String> classIds, @Param("now") Instant now);

    /** Filters do not apply to `em.find`, so the scoped lookup goes through a query. */
    @Query("select a from AnnouncementEntity a where a.id = :id")
    Optional<Entities.AnnouncementEntity> findOneById(@Param("id") String id);
}
