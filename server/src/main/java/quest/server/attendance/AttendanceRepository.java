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
}
