package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * `V28__product_name_myschool.sql`, run against the `platform_settings` row in the states a real database can be in.
 *
 * <p>Flyway applies V28 once, to the freshly seeded row, so the guard — "keep a name an Admin set by hand" — never
 * meets a custom name in any pipeline and would ship unexercised. The statements are read out of the migration file
 * rather than copied, and each case runs them twice to prove the second run is a no-op. Shared by the H2 and the
 * PostgreSQL test so both vendors execute exactly the same cases.
 */
final class ProductRenameMigration {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V28__product_name_myschool.sql");

    private ProductRenameMigration() {}

    record Names(String name, String shortName) {}

    /** Puts the row in the given state, runs the migration twice, answers what it holds, and restores the row. */
    static Names migrate(JdbcTemplate jdbc, String name, String shortName) throws IOException {
        Names before = read(jdbc);
        try {
            jdbc.update("UPDATE platform_settings SET name = ?, short_name = ? WHERE id = 'default'", name, shortName);
            for (int run = 0; run < 2; run++) statements().forEach(jdbc::execute);
            return read(jdbc);
        } finally {
            jdbc.update("UPDATE platform_settings SET name = ?, short_name = ? WHERE id = 'default'", before.name(), before.shortName());
        }
    }

    static Names read(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT name, short_name FROM platform_settings WHERE id = 'default'",
                (rs, i) -> new Names(rs.getString(1), rs.getString(2)));
    }

    /** Every case, on whichever database `jdbc` points at. */
    static void assertGuardedRename(JdbcTemplate jdbc) throws IOException {
        assertThat(read(jdbc)).as("Flyway has already renamed the seeded row").isEqualTo(new Names("MySchool", "MySchool"));
        assertThat(migrate(jdbc, "Schools Dashboard", "Schools")).as("the seeded defaults are renamed").isEqualTo(new Names("MySchool", "MySchool"));
        assertThat(migrate(jdbc, "Acme Learning", "Acme")).as("a name set by hand is kept").isEqualTo(new Names("Acme Learning", "Acme"));
        assertThat(migrate(jdbc, "Acme Learning", "Schools")).as("the short name does not move under a custom name").isEqualTo(new Names("Acme Learning", "Schools"));
        assertThat(migrate(jdbc, "Schools Dashboard", "SD")).as("a short name set by hand is kept").isEqualTo(new Names("MySchool", "SD"));
    }

    /** Comments go first and statements second: a `--` line may itself contain a `;`. */
    private static List<String> statements() throws IOException {
        String sql = Files.readAllLines(MIGRATION, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
        List<String> statements = Arrays.stream(sql.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        assertThat(statements).hasSize(2);
        return statements;
    }
}
