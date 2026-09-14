package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the Flyway migrations and the seed against a real PostgreSQL. Skipped when Docker isn't available. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"quest.auth.fake=true", "quest.llm.provider=fake", "quest.auth.jwt-secret=test-secret-test-secret-test-secret-test-secret", "spring.profiles.active=h2"})
class PostgresRepositoryTest {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl); r.add("spring.datasource.username", postgres::getUsername); r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired LessonRepository lessons; @Autowired CourseRepository courses;

    @Test void migrations_and_seed_run_on_postgres() {
        assertThat(courses.count()).isEqualTo(6);
        assertThat(lessons.findByCourseIdAndStatus("british/1", "published")).hasSizeGreaterThanOrEqualTo(3);
    }
}
