package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * §3 white label and §A naming: what a school looks like, what it is called, and what the platform itself is called.
 * The app reads the first two from `GET /schools/{id}/theme` (and inside `JoinSchoolInfo`), the dashboard maps them
 * onto its CSS custom properties, and neither is rebuilt per school.
 */

/** §3's three child faces. */
@Serializable
enum class FontChoice {
    @SerialName("baloo") BALOO,
    @SerialName("nunito") NUNITO,
    @SerialName("fredoka") FREDOKA,
}

/**
 * One subject's world on the map. [primary], [deep] and [soft] are the shape `design/tokens.json` uses for
 * `worldPalettes.math`; [ink] is the text drawn on that world and [soft] the ground it is drawn on — the pair the
 * server measures for contrast when Admin saves a theme.
 */
@Serializable
data class WorldPalette(
    val primary: String = "#6FC3FF",
    val deep: String = "#3F9BE0",
    val soft: String = "#EAF4FF",
    val ink: String = "#201E1D",
)

/**
 * A school's theme. Every colour is `#RRGGBB`; the server rejects a save where a text/background pair is below
 * 4.5:1 — `primaryInk` on `primary` and on `ground`, `accent` on `ground`, and each world's `ink` on its `soft` —
 * or where [mascotColor] is below 3:1 on [ground] (a graphic, WCAG 1.4.11). So [primary] is a light brand surface
 * rather than a saturated fill, and [accent] is the colour of an action, dark enough to read on [ground].
 *
 * [appName] overrides the platform's name inside this school's scope (§A); a null one falls back to it.
 *
 * The defaults here mirror the theme the server builds from `design/tokens.json` — `DefaultThemeTest` on the server
 * fails if the two drift — so an offline app or a `FakeContentApi` shows the same colours the backend would send.
 */
@Serializable
data class SchoolTheme(
    val logoUrl: String? = null,
    val appName: String? = null,
    val primary: String = "#FFFFFF",
    val primaryInk: String = "#201E1D",
    val accent: String = "#CC2A0F",
    val ground: String = "#F3F2F2",
    val softBorder: String = "#D9D6D2",
    /**
     * The tokens' mascot blue darkened until it clears 3:1 on [ground] — the mascot is a graphic, so WCAG 1.4.11's
     * non-text bar, not the 4.5:1 the five text pairs are held to. Darkened the way `color.accent-strong` was.
     */
    val mascotColor: String = "#598FB8",
    val worldPalettes: Map<String, WorldPalette> = mapOf(
        "math" to WorldPalette(),
        "english" to WorldPalette(primary = "#B69CFF", deep = "#7E63D8", soft = "#F1ECFF"),
    ),
    val fontChoice: FontChoice = FontChoice.NUNITO,
)

/**
 * §A: the platform's own identity, editable by Admin under Platform settings and seeded in `V5__flags_themes.sql`.
 * `GET /platform-settings` is public and fills [name], [shortName] and [logoUrl] only; the Admin route fills all.
 */
@Serializable
data class PlatformSettings(
    val name: String,
    val shortName: String,
    val logoUrl: String? = null,
    val supportEmail: String? = null,
    val defaultTheme: SchoolTheme? = null,
)
