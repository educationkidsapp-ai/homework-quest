package quest.server.management;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * RM5. Every read is by a set of people, never by one person at a time: a department's roster is dozens of teachers
 * and coordinators and the three screens want one statement each — `TenantArchitectureTest` insists on the
 * `@Transactional` that keeps the `school` filter on for the derived and `@Query` methods alike.
 */
@Transactional(readOnly = true)
public interface StaffAttendanceRepository extends JpaRepository<Entities.StaffAttendanceEntity, String> {

    /** One day for the whole department — the roster read, and the rows the upsert overwrites. */
    List<Entities.StaffAttendanceEntity> findByDateAndUserIdIn(LocalDate date, Collection<String> userIds);

    /** One person's day, by the pair the unique index is on — the recovery read of a raced insert. */
    java.util.Optional<Entities.StaffAttendanceEntity> findByUserIdAndDate(String userId, LocalDate date);

    /** One person's marked days inside a window, newest first. */
    List<Entities.StaffAttendanceEntity> findByUserIdAndDateBetweenOrderByDateDesc(String userId, LocalDate from, LocalDate to);

    /**
     * `[userId, status, how many days]` for a month of a whole department in one statement — what the summary folds
     * each person's counts and rate out of. Counted in the database: a term of a department is thousands of rows and
     * the screen wants five numbers per person.
     */
    @Query("select a.userId, a.status, count(a) from StaffAttendanceEntity a"
            + " where a.userId in :userIds and a.date between :from and :to group by a.userId, a.status")
    List<Object[]> countByStatusInWindow(@Param("userIds") Collection<String> userIds,
                                        @Param("from") LocalDate from, @Param("to") LocalDate to);
}
