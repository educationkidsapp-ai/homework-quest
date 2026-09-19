package quest.server.classes;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same four tests on PostgreSQL 16, which is what QA runs. A wipe is exactly the kind of code that passes on one
 * engine and fails on the other — `DELETE … IN (SELECT …)` chains, the order the foreign keys allow, a `TIMESTAMP`
 * bound from a `java.sql.Timestamp` — so the H2 run alone would not be evidence that the owner's QA can be emptied.
 *
 * <p><strong>Its own container, deliberately.</strong> {@link quest.server.PostgresContainerSupport} holds one
 * PostgreSQL for the whole suite and hands every subclass the same Spring context; this class empties every school
 * in the database it is pointed at, so sharing that one would poison it for
 * `PostgresRepositoryTest` and `PostgresReportsTest`. The container below is therefore a separate instance with a
 * database name of its own and <strong>no</strong> `withReuse` — a reused container is matched by its configuration,
 * and an identically configured one would be handed back the suite's own.
 *
 * <p>Tagged `postgres` like the shared support class, so CI's second server step
 * (`./mvnw test -Dtest.groups=postgres`) runs it and the first one skips it. This Mac has no Docker, so
 * `disabledWithoutDocker` skips the class locally.
 */
@Tag("postgres")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"spring.profiles.active=h2", "quest.seed.school=false", "quest.auth.fake=true",
        "quest.llm.provider=fake", "quest.auth.jwt-secret=test-secret-test-secret-test-secret-test-secret",
        "quest.admin.seed-email=", "quest.admin.seed-password=",
        "quest.storage.local-dir=${java.io.tmpdir}/quest-seedreset-files"})
class SeedResetPostgresTest extends SeedResetSupport {
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("seedreset");

    static {
        if (DockerClientFactory.instance().isDockerAvailable() && !POSTGRES.isRunning()) POSTGRES.start();
    }

    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
