package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import quest.server.PostgresContainerSupport;

/**
 * Runs the Flyway migrations and the seed against a real PostgreSQL. Skipped when Docker isn't available.
 *
 * <p>The container and the {@code @SpringBootTest} properties come from {@link PostgresContainerSupport}, so every
 * {@code postgres}-tagged class shares one database and one Spring context.
 */
class PostgresRepositoryTest extends PostgresContainerSupport {
    @Autowired LessonRepository lessons; @Autowired CourseRepository courses;

    @Test void migrations_and_seed_run_on_postgres() {
        assertThat(courses.count()).isEqualTo(6);
        assertThat(lessons.findByCourseIdAndStatus("british/1", "published")).hasSizeGreaterThanOrEqualTo(3);
    }
}
