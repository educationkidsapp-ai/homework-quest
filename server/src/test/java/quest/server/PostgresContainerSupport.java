package quest.server;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One PostgreSQL 16 container for the whole suite, not one per test class (P-CI).
 *
 * <p>The container is a {@code static} singleton started once, on first use, and left to Ryuk to stop when the JVM
 * exits — the {@code @Container} annotation would start and stop a fresh database for every class that needs one,
 * which is the slowest thing the server job does. Because the {@code @SpringBootTest} properties live here too, every
 * subclass asks for the identical context and Spring's context cache hands out the same one.
 *
 * <p>{@code withReuse(true)} does nothing on a CI runner (the machine is thrown away) and everything on a developer
 * machine that has Docker: add
 *
 * <pre>testcontainers.reuse.enable=true</pre>
 *
 * to {@code ~/.testcontainers.properties} and the same container survives between runs of {@code ./mvnw test}, so the
 * migrations and the seed run once a day instead of once a run. This Mac has no Docker at all, so
 * {@code @Testcontainers(disabledWithoutDocker = true)} skips every subclass here and the static block never runs.
 *
 * <p>Tagged {@code postgres} so CI can report the H2 half of the suite first:
 * {@code ./mvnw test -Dtest.excludedGroups=postgres} then {@code ./mvnw test -Dtest.groups=postgres}.
 */
@Tag("postgres")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"quest.auth.fake=true", "quest.llm.provider=fake",
        "quest.auth.jwt-secret=test-secret-test-secret-test-secret-test-secret", "spring.profiles.active=h2"})
public abstract class PostgresContainerSupport {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withReuse(true);

    static {
        // Guarded as well as annotated: a subclass that forgets @Testcontainers still fails as "skipped", not as a
        // connection error, on a machine without Docker.
        if (DockerClientFactory.instance().isDockerAvailable() && !POSTGRES.isRunning()) POSTGRES.start();
    }

    @DynamicPropertySource static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
