package quest.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.PostgresContainerSupport;

/**
 * MA1's hand-written statements, on the database QA runs on — what the H2 half of the suite cannot prove.
 *
 * <p>Three of them are new shapes rather than new rows: `V25__workers_parent_names.sql` itself; the Admin Home's
 * <strong>five scalar subqueries in one `SELECT` with no `FROM`</strong>, which is the one construct in MA1 that H2 in
 * PostgreSQL mode could accept and PostgreSQL reject; and the Children &amp; parents page's `like … escape '\'` over an
 * `exists` that now also reaches the parent's name and telephone number — an escape clause that works on one database
 * and not the other would widen a search silently (`PostgresStaffAttendanceTest`'s reason, one screen over).
 *
 * <p>It asserts that each statement <em>shapes an answer</em>, not what the numbers are: those are
 * {@link AdminPeopleApiTest}'s, and a second copy here would only be a second place to update. The container is shared
 * with every other `postgres`-tagged class, so this writes nothing.
 */
class PostgresAdminPeopleTest extends PostgresContainerSupport {
    @Autowired quest.server.workers.WorkerRepository workers;
    @Autowired quest.server.children.ChildRepository children;
    @Autowired quest.server.auth.ParentRepository parents;
    @Autowired jakarta.persistence.EntityManager em;

    @Test void v25_ran_and_the_workers_table_answers() {
        assertThat(workers.count()).isNotNegative();
        assertThat(workers.findBySchoolIdOrderByFullNameAsc("pg-no-such-school")).isEmpty();
        assertThat(workers.countBySchoolIdAndActiveTrue("pg-no-such-school")).isZero();
        assertThat(parents.findFirstByEmailIgnoreCase("nobody@pg.test")).isEmpty();
    }

    @Test void the_admin_homes_five_counts_run_as_one_statement_with_no_from_clause() {
        var row = (Object[]) em.createNativeQuery("SELECT "
                + "(SELECT COUNT(*) FROM users WHERE role = 'MANAGERIAL' AND status <> 'disabled' AND school_id = :schoolId), "
                + "(SELECT COUNT(*) FROM users WHERE role = 'COORDINATOR' AND status <> 'disabled' AND school_id = :schoolId), "
                + "(SELECT COUNT(*) FROM users WHERE role = 'TEACHER' AND status <> 'disabled' AND school_id = :schoolId), "
                + "(SELECT COUNT(*) FROM classes WHERE 1 = 1 AND school_id = :schoolId), "
                + "(SELECT COUNT(*) FROM workers WHERE active = TRUE AND school_id = :schoolId)")
                .setParameter("schoolId", "default").getSingleResult();
        assertThat(row).hasSize(5);
        for (Object value : row) assertThat(((Number) value).longValue()).isNotNegative();
    }

    @Test void the_children_and_parents_page_and_its_escape_clause_run_on_postgres() {
        // The pattern a search box for `100%` becomes: the escaped `%` must reach PostgreSQL as one.
        for (String q : List.of("%", "%100\\%\\_off%", "%\\_%", "%\\\\%")) {
            assertThat(children.countSchoolDirectory("pg-no-such-school", q)).isZero();
            assertThat(children.findSchoolDirectory("pg-no-such-school", q,
                    org.springframework.data.domain.PageRequest.of(0, 5))).isEmpty();
        }
        assertThat(children.findSchoolIdsOfParentAcrossSchools("pg-nobody")).isEmpty();
    }
}
