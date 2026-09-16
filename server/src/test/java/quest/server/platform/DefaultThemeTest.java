package quest.server.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The default theme is built from `design/tokens.json` (§3 "Default theme = the one in `docs/design.md`"), and three
 * things have to stay true of it: the copy the server ships is the repository's file, the theme passes the very
 * validation Admin's saves go through, and the app's offline default (`quest.api.dto.SchoolTheme()` in shared-api,
 * which cannot read the tokens file) is the same theme.
 */
class DefaultThemeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DesignTokens tokens = new DesignTokens(mapper);

    /** The server's classpath copy exists because the Docker build context is `server/` alone — it must not drift. */
    @Test void the_shipped_tokens_are_the_repositorys_tokens() throws Exception {
        Path repository = Path.of("..", "design", "tokens.json");
        Path shipped = Path.of("src", "main", "resources", DesignTokens.RESOURCE);
        assertThat(shipped).exists();
        if (!Files.exists(repository)) return;                       // not a repository checkout (a Docker build)
        assertThat(Files.readString(shipped, StandardCharsets.UTF_8))
                .as("server/src/main/resources/%s is the copy the jar ships; run `cp design/tokens.json server/src/main/resources/%s`",
                        DesignTokens.RESOURCE, DesignTokens.RESOURCE)
                .isEqualTo(Files.readString(repository, StandardCharsets.UTF_8));
    }

    @Test void the_default_theme_is_built_from_the_tokens() {
        var theme = tokens.defaultTheme();
        assertThat(theme.ground()).isEqualTo(tokens.colour("bg"));
        assertThat(theme.primary()).isEqualTo(tokens.colour("surface"));
        assertThat(theme.primaryInk()).isEqualTo(tokens.colour("ink"));
        assertThat(theme.softBorder()).isEqualTo(tokens.colour("rule"));
        // `color.accent` (#EC3013) is 3.8:1 on the ground and would be rejected; accent-strong is the tokens' own fix.
        assertThat(theme.accent()).isEqualTo(tokens.colour("accent-strong"));
        assertThat(Contrast.ratio(tokens.colour("accent"), theme.ground())).isLessThan(Contrast.MINIMUM);
        assertThat(theme.worldPalettes()).containsOnlyKeys("math", "english");
        assertThat(theme.fontChoice()).isEqualTo(ThemeDto.FontChoice.NUNITO);
    }

    /** Every pair `ThemeService` measures, on the theme every school starts with. */
    @Test void the_default_theme_passes_its_own_validation() {
        var theme = tokens.defaultTheme();
        assertThat(Contrast.ratio(theme.primaryInk(), theme.primary())).isGreaterThanOrEqualTo(Contrast.MINIMUM);
        assertThat(Contrast.ratio(theme.primaryInk(), theme.ground())).isGreaterThanOrEqualTo(Contrast.MINIMUM);
        assertThat(Contrast.ratio(theme.accent(), theme.ground())).isGreaterThanOrEqualTo(Contrast.MINIMUM);
        // the mascot is a graphic, so 1.4.11's 3:1 — and it is derived to sit just above it, not far above
        assertThat(Contrast.ratio(theme.mascotColor(), theme.ground())).isGreaterThanOrEqualTo(Contrast.MINIMUM_NON_TEXT);
        assertThat(Contrast.ratio(theme.mascotColor(), theme.ground()))
                .as("darkened no further than the bar asks, so the mascot stays as close to the tokens' blue as it can")
                .isLessThan(Contrast.MINIMUM);
        for (String world : ThemeDto.WORLDS) {
            var palette = theme.worldPalettes().get(world);
            assertThat(Contrast.ratio(palette.ink(), palette.soft())).as(world).isGreaterThanOrEqualTo(Contrast.MINIMUM);
        }
    }

    /**
     * shared-api cannot read `design/tokens.json` (it has no file access on iOS or in the browser), so
     * `SchoolTheme()` carries the same values as literals. If the tokens change, this fails and those literals — and
     * only those — have to follow.
     */
    @Test void the_shared_api_default_is_the_same_theme() {
        var shared = new quest.api.dto.SchoolTheme();
        var theme = tokens.defaultTheme();
        assertThat(shared.getPrimary()).isEqualTo(theme.primary());
        assertThat(shared.getPrimaryInk()).isEqualTo(theme.primaryInk());
        assertThat(shared.getAccent()).isEqualTo(theme.accent());
        assertThat(shared.getGround()).isEqualTo(theme.ground());
        assertThat(shared.getSoftBorder()).isEqualTo(theme.softBorder());
        assertThat(shared.getMascotColor()).isEqualTo(theme.mascotColor());
        assertThat(shared.getFontChoice().name()).isEqualTo(theme.fontChoice().name());
        for (String world : ThemeDto.WORLDS) {
            var sharedWorld = shared.getWorldPalettes().get(world);
            var palette = theme.worldPalettes().get(world);
            assertThat(sharedWorld).as(world).isNotNull();
            assertThat(sharedWorld.getPrimary()).isEqualTo(palette.primary());
            assertThat(sharedWorld.getDeep()).isEqualTo(palette.deep());
            assertThat(sharedWorld.getSoft()).isEqualTo(palette.soft());
            assertThat(sharedWorld.getInk()).isEqualTo(palette.ink());
        }
    }

    @Test void contrast_follows_the_wcag_definition() {
        assertThat(Contrast.ratio("#000000", "#FFFFFF")).isEqualTo(21.0);
        assertThat(Contrast.ratio("#FFFFFF", "#FFFFFF")).isEqualTo(1.0);
        assertThat(Contrast.format(Contrast.ratio("#777777", "#FFFFFF"))).isEqualTo("4.5");
        assertThat(Contrast.isHex("#A1B2C3")).isTrue();
        assertThat(Contrast.isHex("#abc")).isFalse();
        assertThat(Contrast.isHex("red")).isFalse();
        assertThat(Contrast.isHex(null)).isFalse();
    }

    @Test void darken_until_stops_at_the_threshold_and_leaves_dark_colours_alone() {
        String ground = tokens.colour("bg");
        String blue = tokens.defaultTheme().worldPalettes().get("math").primary();
        assertThat(Contrast.ratio(Contrast.darkenUntil(blue, ground, Contrast.MINIMUM), ground)).isGreaterThanOrEqualTo(Contrast.MINIMUM);
        assertThat(Contrast.ratio(Contrast.darkenUntil(blue, ground, Contrast.MINIMUM_NON_TEXT), ground)).isGreaterThanOrEqualTo(Contrast.MINIMUM_NON_TEXT);
        assertThat(Contrast.darkenUntil("#201E1D", ground, Contrast.MINIMUM)).isEqualTo("#201E1D");
    }
}
