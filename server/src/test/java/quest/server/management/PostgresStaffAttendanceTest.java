package quest.server.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.PostgresContainerSupport;

/**
 * RM5's hand-written statements, on the database QA runs on. `V21__staff_attendance.sql`, the summary's
 * `group by a.userId, a.status` and the directory's three `like … escape '\\'`s over an `exists` are what the H2 half
 * of the suite cannot prove: H2 in PostgreSQL mode is a good approximation of a `CHECK` constraint, a composite unique
 * index, a grouped aggregate and an escape clause, and not the thing itself — an escape clause that works on one and
 * not the other would widen a search silently.
 *
 * <p>It asserts that the migration ran and the statement <em>shapes an answer</em>, not what the numbers are — those
 * are {@link ManagementPeopleApiTest}'s, and a second copy of them here would only be a second place to update. The
 * container is shared with every other `postgres`-tagged class, so this reads and writes nothing.
 */
class PostgresStaffAttendanceTest extends PostgresContainerSupport {
    @Autowired StaffAttendanceRepository register;
    @Autowired quest.server.children.ChildRepository children;

    @Test void the_staff_register_and_its_grouped_summary_run_on_postgres() {
        LocalDate today = LocalDate.now();
        assertThat(register.count()).isNotNegative();
        assertThat(register.findByDateAndUserIdIn(today, List.of("pg-nobody"))).isEmpty();
        assertThat(register.findByUserIdAndDateBetweenOrderByDateDesc("pg-nobody", today.minusMonths(1), today)).isEmpty();
        assertThat(register.countByStatusInWindow(List.of("pg-nobody"), today.withDayOfMonth(1), today)).isEmpty();
    }

    @Test void the_directory_statement_and_its_escape_clause_run_on_postgres() {
        var sections = List.of("pg-no-such-section");
        // The pattern a search box for `100%` becomes: the escaped `%` must reach PostgreSQL as one.
        for (String q : List.of("%", "%100\\%\\_off%", "%\\_%", "%\\\\%")) {
            assertThat(children.countDirectory(sections, q)).isZero();
            assertThat(children.findDirectory(sections, q, org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
        }
    }
}
