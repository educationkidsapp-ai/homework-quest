package quest.server.platform;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Locale;
import java.util.Map;

/** The §3 theme JSON: the Java mirror of `quest.api.dto.SchoolTheme`, as records so springdoc can describe it. */
public final class ThemeDto {
    private ThemeDto() {}

    /** The subjects a world palette may be given for; §3's `worldPalettes: { math, english }`. */
    public static final java.util.List<String> WORLDS = java.util.List.of("math", "english");

    /**
     * One subject's world on the map. `primary`, `deep` and `soft` are the shape `design/tokens.json` already uses
     * (`worldPalettes.math`); `ink` is the text drawn on that world, and `soft` is the ground it is drawn on — the
     * pair {@link ThemeService} measures for contrast.
     */
    public record WorldPalette(String primary, String deep, String soft, String ink) {}

    /** §3's three child faces. Serialised lowercase, so the JSON reads `"fontChoice": "nunito"`. */
    public enum FontChoice {
        BALOO, NUNITO, FREDOKA;

        @JsonValue public String json() { return name().toLowerCase(Locale.ROOT); }

        @JsonCreator public static FontChoice from(String value) {
            if (value == null) return NUNITO;
            for (FontChoice choice : values()) if (choice.json().equalsIgnoreCase(value.trim())) return choice;
            throw new IllegalArgumentException("fontChoice is baloo, nunito or fredoka, not " + value);
        }
    }

    /**
     * What a school looks like (§3). Every colour is `#RRGGBB`; `appName` overrides the platform name inside this
     * school's scope (§A) and `logoUrl` is the logo shown on the map header and the sign-in page.
     *
     * <p>`primaryInk` is text on <em>both</em> `primary` and `ground`, so `primary` is a light brand surface rather
     * than a saturated fill; `accent` is the colour of an action and is measured on `ground`.
     */
    public record SchoolTheme(
            String logoUrl,
            String appName,
            @Schema(example = "#FFFFFF") String primary,
            @Schema(example = "#201E1D") String primaryInk,
            @Schema(example = "#CC2A0F") String accent,
            @Schema(example = "#F3F2F2") String ground,
            @Schema(example = "#D9D6D2") String softBorder,
            @Schema(example = "#598FB8") String mascotColor,
            Map<String, WorldPalette> worldPalettes,
            FontChoice fontChoice) {}
}
