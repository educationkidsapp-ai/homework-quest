package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import quest.server.ApiTestSupport;
import quest.server.config.Json;

/**
 * `V29__default_theme_logo_palette.sql`, run against rows that look the way themed rows looked before B2.
 *
 * <p>Flyway applies V29 to an empty database here and in CI, so the update itself would ship unexercised. The
 * statements are read out of the migration file, the rows are written by the very code that writes them in
 * production ({@link ThemeService#validated} + {@link Json#write}) so the guard is measured against the real stored
 * shape, and the file is run twice to prove the second run changes nothing.
 */
class DefaultThemePaletteMigrationTest extends ApiTestSupport {
    private static final Path MIGRATION = Path.of("src/main/resources/db/migration/V29__default_theme_logo_palette.sql");
    private static final String PREFIX = "b2-", OLD_ACCENT = "#CC2A0F", NEW_ACCENT = "#0762BF";

    @Autowired JdbcTemplate jdbc;
    @Autowired ThemeService themes;
    @Autowired DesignTokens tokens;
    @Autowired Json json;

    @AfterEach void cleanUp() {
        jdbc.update("DELETE FROM schools WHERE id LIKE ?", PREFIX + "%");
        jdbc.update("UPDATE platform_settings SET default_theme_json = NULL WHERE id = 'default'");
    }

    @Test void only_a_theme_still_holding_the_old_default_moves_to_the_logo_blue() throws Exception {
        var current = tokens.defaultTheme();
        assertThat(current.accent()).isEqualTo(NEW_ACCENT);
        // the old default as the wizard saved it: every colour the default's, plus the school's own name, logo and face
        String untouched = stored(new ThemeDto.SchoolTheme("https://cdn.example.test/noor.png", "Al Noor", current.primary(), current.primaryInk(),
                OLD_ACCENT, current.ground(), current.softBorder(), current.mascotColor(), current.worldPalettes(), ThemeDto.FontChoice.BALOO));
        // the old red kept on purpose, with a ground of the school's own: customised, so it stays
        String ownGround = stored(new ThemeDto.SchoolTheme(null, "Red School", current.primary(), current.primaryInk(),
                OLD_ACCENT, "#FFFFFF", current.softBorder(), current.mascotColor(), current.worldPalettes(), ThemeDto.FontChoice.NUNITO));
        String ownAccent = stored(new ThemeDto.SchoolTheme(null, "Green School", current.primary(), current.primaryInk(),
                "#0B5D2E", current.ground(), current.softBorder(), current.mascotColor(), current.worldPalettes(), ThemeDto.FontChoice.NUNITO));
        school("default-colours", "BTWOAA", untouched);
        school("own-ground", "BTWOAB", ownGround);
        school("own-accent", "BTWOAC", ownAccent);
        school("no-theme", "BTWOAD", null);
        jdbc.update("UPDATE platform_settings SET default_theme_json = ? WHERE id = 'default'", untouched);

        migrate();

        var moved = json.read(themeOf("default-colours"), ThemeDto.SchoolTheme.class);
        assertThat(moved.accent()).isEqualTo(NEW_ACCENT);
        assertThat(themeOf("default-colours")).as("nothing but the accent changed").isEqualTo(untouched.replace(OLD_ACCENT, NEW_ACCENT));
        assertThat(moved.appName()).isEqualTo("Al Noor");
        assertThat(moved.fontChoice()).isEqualTo(ThemeDto.FontChoice.BALOO);
        assertThat(themes.validated(moved).accent()).as("the moved theme still passes the contrast rule").isEqualTo(NEW_ACCENT);
        assertThat(themeOf("own-ground")).isEqualTo(ownGround);
        assertThat(themeOf("own-accent")).isEqualTo(ownAccent);
        assertThat(themeOf("no-theme")).isNull();
        String platformDefault = jdbc.queryForObject("SELECT default_theme_json FROM platform_settings WHERE id = 'default'", String.class);
        assertThat(platformDefault).isEqualTo(untouched.replace(OLD_ACCENT, NEW_ACCENT));

        Map<String, Object> before = jdbc.queryForMap("SELECT theme_json FROM schools WHERE id = ?", PREFIX + "default-colours");
        migrate();
        assertThat(jdbc.queryForMap("SELECT theme_json FROM schools WHERE id = ?", PREFIX + "default-colours")).as("a second run is a no-op").isEqualTo(before);
        assertThat(themeOf("own-ground")).isEqualTo(ownGround);
    }

    private String stored(ThemeDto.SchoolTheme theme) { return json.write(themes.validated(theme)); }

    private String themeOf(String id) { return jdbc.queryForObject("SELECT theme_json FROM schools WHERE id = ?", String.class, PREFIX + id); }

    private void school(String id, String code, String themeJson) {
        jdbc.update("INSERT INTO schools (id, name, code, curriculum_options_json, grade_options_json, theme_json, feature_flags_json, status, created_at)"
                + " VALUES (?, ?, ?, '[\"british\"]', '[1,2,3]', ?, '{}', 'active', CURRENT_TIMESTAMP)", PREFIX + id, "B2 " + id, code, themeJson);
    }

    private void migrate() throws Exception {
        var sql = new StringBuilder();
        // Comments go first and statements second: a `--` line may itself contain a `;`.
        for (String line : Files.readString(MIGRATION, StandardCharsets.UTF_8).split("\n")) if (!line.trim().startsWith("--")) sql.append(line).append('\n');
        int statements = 0;
        for (String raw : sql.toString().split(";")) if (!raw.isBlank()) { jdbc.execute(raw.trim()); statements++; }
        assertThat(statements).isEqualTo(2);
    }
}
