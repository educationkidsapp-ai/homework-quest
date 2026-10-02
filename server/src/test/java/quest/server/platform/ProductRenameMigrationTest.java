package quest.server.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.server.ApiTestSupport;

/** N1: the guarded rename of `V29__product_name_myschool.sql` on H2 in PostgreSQL mode (the test profile). */
class ProductRenameMigrationTest extends ApiTestSupport {
    @Autowired JdbcTemplate jdbc;

    @Test void the_seeded_name_becomes_myschool_and_a_custom_name_is_kept() throws Exception {
        ProductRenameMigration.assertGuardedRename(jdbc);
    }
}
