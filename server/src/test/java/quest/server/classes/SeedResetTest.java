package quest.server.classes;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * The wipe on H2, against a database of its own: the rest of the suite shares `questtest`, and these tests delete
 * every school-scoped row there is. {@link SeedResetPostgresTest} runs the same four on PostgreSQL 16.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:seedreset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class SeedResetTest extends SeedResetSupport {
}
