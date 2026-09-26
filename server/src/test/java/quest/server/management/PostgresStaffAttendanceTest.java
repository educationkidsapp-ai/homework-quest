package quest.server.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.PostgresContainerSupport;

/**
 * RM5's one grouped statement, on the database QA runs on. `V22__staff_attendance.sql` and the summary's
 * `group by a.userId, a.status` are the two things the H2 half of the suite cannot prove: H2 in PostgreSQL mode is a
 * good approximation of a `CHECK` constraint, a composite unique index and a grouped aggregate, and not the thing
 * itself.
 *
 * <p>It asserts that the migration ran and the statement <em>shapes an answer</em>, not what the numbers are — those
 * are {@link ManagementPeopleApiTest}'s, and a second copy of them here would only be a second place to update. The
 * container is shared with every other `postgres`-tagged class, so this reads and writes nothing.
 */
class PostgresStaffAttendanceTest extends PostgresContainerSupport {
    @Autowired StaffAttendanceRepository register;

    @Test void the_staff_register_and_its_grouped_summary_run_on_postgres() {
        LocalDate today = LocalDate.now();
        assertThat(register.count()).isNotNegative();
        assertThat(register.findByDateAndUserIdIn(today, List.of("pg-nobody"))).isEmpty();
        assertThat(register.findByUserIdAndDateBetweenOrderByDateDesc("pg-nobody", today.minusMonths(1), today)).isEmpty();
        assertThat(register.countByStatusInWindow(List.of("pg-nobody"), today.withDayOfMonth(1), today)).isEmpty();
    }
}
