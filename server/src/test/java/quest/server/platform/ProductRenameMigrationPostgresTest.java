package quest.server.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.server.PostgresContainerSupport;

/** N1: the same cases as {@link ProductRenameMigrationTest}, on PostgreSQL 16. Skipped when Docker isn't available. */
class ProductRenameMigrationPostgresTest extends PostgresContainerSupport {
    @Autowired JdbcTemplate jdbc;

    @Test void the_seeded_name_becomes_myschool_and_a_custom_name_is_kept() throws Exception {
        ProductRenameMigration.assertGuardedRename(jdbc);
    }
}
