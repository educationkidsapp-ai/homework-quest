package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * §A: "Nothing in the code base hard-codes a product name; an ESLint rule and an ArchUnit test fail the build on the
 * literal `Homework Quest` or `Schools Dashboard` outside the seed and the tests."
 *
 * <p>The seed is `db/migration/V5__flags_themes.sql`, which is where the name enters the system; the tests are
 * `src/test`, which has to spell the seeded value out to assert on it. Everything else — including a comment or a
 * model prompt — asks {@link PlatformSettingsService} instead.
 */
class ProductNameTest {
    private static final List<String> FORBIDDEN = List.of("Homework Quest", "Schools Dashboard");
    private static final Path MAIN = Path.of("src", "main", "java");

    @Test void no_source_file_spells_out_a_product_name() throws IOException {
        var offenders = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String name : FORBIDDEN)
                    if (text.contains(name)) offenders.add(file + " contains \"" + name + "\"");
            }
        }
        assertThat(offenders)
                .as("the product's name is data (§A): read it from PlatformSettingsService, do not write it into %s", MAIN)
                .isEmpty();
    }

    /** The sweep has to be looking at the real tree, or it would pass on an empty one. */
    @Test void the_sweep_sees_the_whole_server() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).count()).isGreaterThan(50);
        }
    }

    /** Where the name does live: the migration seeds it, and nothing else in `src/main` repeats it. */
    @Test void the_seed_migration_is_the_one_place_the_name_appears() throws IOException {
        var seed = Files.readString(Path.of("src", "main", "resources", "db", "migration", "V5__flags_themes.sql"), StandardCharsets.UTF_8);
        assertThat(seed).contains("'Schools Dashboard'").contains("'Schools'");

        var elsewhere = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "resources"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (file.toString().contains("db" + java.io.File.separator + "migration")) continue;
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String name : FORBIDDEN) if (text.contains(name)) elsewhere.add(file + " contains \"" + name + "\"");
            }
        }
        assertThat(elsewhere).as("resources other than the migrations must not name the product either").isEmpty();
    }
}
