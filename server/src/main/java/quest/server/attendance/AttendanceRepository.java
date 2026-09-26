package quest.server.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface AttendanceRepository extends JpaRepository<AttendanceEntity, String> {

    List<AttendanceEntity> findBySectionIdAndDate(String sectionId, LocalDate date);

    /** R3: a window of one section, for `/coordinator/classes/{id}/attendance` — one statement, not one per day. */
    List<AttendanceEntity> findBySectionIdAndDateBetweenOrderByDateAsc(String sectionId, LocalDate from, LocalDate to);

    Optional<AttendanceEntity> findByChildIdAndDate(String childId, LocalDate date);

    List<AttendanceEntity> findByChildIdAndDateBetweenOrderByDateDesc(String childId, LocalDate from, LocalDate to);

    List<AttendanceEntity> findByChildIdOrderByDateDesc(String childId);

    long countBySectionIdAndDateAndStatus(String sectionId, LocalDate date, String status);

    /**
     * RM1: `[sectionId, status, how many rows]` over a window, for every section of a department in one statement —
     * what `GET /management/stats` folds each grade's attendance rate out of. Counted in the database because a term
     * of a whole department is tens of thousands of rows and the screen wants one percentage per grade.
     */
    @org.springframework.data.jpa.repository.Query("select a.sectionId, a.status, count(a) from AttendanceEntity a"
            + " where a.sectionId in :sectionIds and a.date between :from and :to group by a.sectionId, a.status")
    List<Object[]> countByStatusInWindow(@org.springframework.data.repository.query.Param("sectionIds") java.util.Collection<String> sectionIds,
                                         @org.springframework.data.repository.query.Param("from") LocalDate from,
                                         @org.springframework.data.repository.query.Param("to") LocalDate to);
}
