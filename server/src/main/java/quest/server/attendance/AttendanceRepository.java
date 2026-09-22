package quest.server.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceRepository extends JpaRepository<AttendanceEntity, String> {

    List<AttendanceEntity> findBySectionIdAndDate(String sectionId, LocalDate date);

    Optional<AttendanceEntity> findByChildIdAndDate(String childId, LocalDate date);

    List<AttendanceEntity> findByChildIdAndDateBetweenOrderByDateDesc(String childId, LocalDate from, LocalDate to);

    List<AttendanceEntity> findByChildIdOrderByDateDesc(String childId);

    long countBySectionIdAndDateAndStatus(String sectionId, LocalDate date, String status);
}
