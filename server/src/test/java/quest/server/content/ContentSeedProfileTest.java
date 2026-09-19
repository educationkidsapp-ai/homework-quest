package quest.server.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

/**
 * The §6 sample lessons belong to a developer's database and to this suite, and nowhere else.
 *
 * <p>`qa` was in the list, and {@link ContentSeed} is a `CommandLineRunner` ordered after
 * {@link quest.server.classes.SeedReset} — so the one-shot wipe emptied QA and this wrote `lesson-counting-by-2s`,
 * `lesson-sh-sound` and `lesson-hot-soup-1` straight back into it, and the owner's acceptance pass opened on three
 * lessons no teacher had posted. A unit test rather than a comment, because the next profile added to that list will
 * be added by someone who has not read the comment.
 */
class ContentSeedProfileTest {

    @Test void the_sample_lessons_seed_only_into_a_developer_or_test_database() {
        var profiles = ContentSeed.class.getAnnotation(Profile.class).value();
        assertThat(profiles).containsExactlyInAnyOrder("local", "dev", "h2", "test");
        assertThat(profiles).as("QA is the owner's environment and prod is nobody's sandbox").doesNotContain("qa", "prod");
    }
}
