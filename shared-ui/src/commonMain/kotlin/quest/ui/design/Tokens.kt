package quest.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Every visual token from docs/design.md lives here. Swap this file to re-skin the app.
 *
 * Child mode (docs/design.md §2) is hand-written: it is not part of `design/tokens.json`, which describes the
 * parent/admin system only. Parent mode and the admin measurements read from [DesignTokens], generated from
 * `design/tokens.json` by `:shared-ui:generateDesignTokens` — the same file the Angular dashboard generates its
 * CSS custom properties from. `./gradlew :shared-ui:checkTokens` fails when the two drift apart.
 */
object Palette {
    val sky = Color(0xFFEAF4FF)
    val cream = Color(0xFFFFF8EC)
    val ink = Color(0xFF1F2A44)
    val inkSoft = Color(0xFF5B6B8C)
    val sun = Color(0xFFFFC93C)
    val sunDeep = Color(0xFFF0A400)
    val mint = Color(0xFF5FD6A5)
    val lavender = Color(0xFFB69CFF)
    val coral = Color(0xFFFF8A65)
    val peach = Color(0xFFFFD9C2)
    val sea = Color(0xFF6FC3FF)
    val seaDeep = Color(0xFF3F9BE0)
    val sand = Color(0xFFF6E3B4)
    val night = Color(0xFF2D3561)
    val white = Color(0xFFFFFFFF)

    // Parent mode + admin panel: flat Modernist — Archivo, square corners, 2px ink rules, one red accent.
    // Generated from design/tokens.json (`hq.color.*`); never hard-code these again.
    val parentBg = DesignTokens.colorBg              // ground
    val parentSurface = DesignTokens.colorSurface
    val parentInk = DesignTokens.colorInk            // text and rules
    val parentInkSoft = DesignTokens.colorInkSoft
    val parentAccent = DesignTokens.colorAccent      // the red
    val parentAccentSoft = DesignTokens.colorAccentSoft // hover tint / red band background

    /**
     * The same red darkened until white-on-red and red-on-ground both clear 4.5:1. Use it for accent TEXT and for
     * fills that carry text; [parentAccent] stays the brand red for rules and glyphs.
     */
    val parentAccentStrong = DesignTokens.colorAccentStrong
    val parentLine = DesignTokens.colorLine
    val parentRule = DesignTokens.colorRule          // light rules, disabled
    val parentDisabled = DesignTokens.colorDisabled

    /** Progress bands, never red. */
    val bandGood = DesignTokens.colorBandGood
    val bandMid = DesignTokens.colorBandMid
    val bandLook = DesignTokens.colorBandLook
}

/**
 * Admin panel (web) measurements — the only place sizes for `webAdmin/` are defined.
 * Generated from design/tokens.json (`hq.size.*`).
 */
object AdminTokens {
    val navWidth = DesignTokens.sizeNavWidth
    val contentMaxWidth = DesignTokens.sizeContentMaxWidth
    val rule = DesignTokens.sizeRule
    val ruleThin = DesignTokens.sizeRuleThin
    val selectedBorder = DesignTokens.sizeSelectedBorder
    val rowHeight = DesignTokens.sizeRowHeight
    val buttonHeight = DesignTokens.sizeButtonHeight
    val inputHeight = DesignTokens.sizeInputHeight
    val pagePadding = DesignTokens.sizePagePadding
    val gutter = DesignTokens.sizeGutter
    val courseCard = DesignTokens.sizeCourseCard
    val gradeCard = DesignTokens.sizeGradeCard
    val phoneWidth = DesignTokens.sizePhoneWidth
    val phoneHeight = DesignTokens.sizePhoneHeight
    val phoneBezel = DesignTokens.sizePhoneBezel
    val phoneCorner = DesignTokens.sizePhoneCorner
    val progressBar = DesignTokens.sizeProgressBar
    val spinner = DesignTokens.sizeSpinner
    val dropZoneHeight = DesignTokens.sizeDropZoneHeight
    val stopListWidth = DesignTokens.sizeStopListWidth
    val logoSize = DesignTokens.sizeLogoSize
}

object Dimens {
    val tileWidth = 176.dp
    val tileHeight = 100.dp
    val tileGap = 12.dp
    val minTarget = 64.dp
    val readAloud = 64.dp
    val radiusTile = 24.dp
    val radiusCard = 28.dp
    val radiusSheet = 32.dp
    val radiusParent = DesignTokens.sizeRadius   // square corners in parent mode / admin
    val pipSmall = 96.dp
    val pipMedium = 140.dp
    val pipLarge = 200.dp
    val s4 = 4.dp
    val s8 = 8.dp
    val s12 = 12.dp
    val s16 = 16.dp
    val s24 = 24.dp
    val s32 = 32.dp
}

object Timing {
    const val correctOverlayMillis = 1_600L
    const val starPopMillis = 350
}

/** Pip's four avatar colours (add-child screen). */
object AvatarColors {
    val keys = listOf("sky", "sun", "mint", "lavender")

    /**
     * Pip as the mascot rather than as a child's avatar: the key a screen passes when Pip stands for the app, not for
     * a child. A school theme's `mascotColor` recolours only this one, so the four avatar swatches keep their meaning.
     * It is not in [keys] and falls through to the sky body, so an unthemed app looks exactly as before.
     */
    const val MASCOT = "mascot"

    fun body(key: String): Color = when (key) { "sun" -> Color(0xFFFFD35C); "mint" -> Color(0xFF7EE0BA); "lavender" -> Color(0xFFC3ADFF); else -> Color(0xFF7EC8FF) }
    fun bodyDark(key: String): Color = when (key) { "sun" -> Color(0xFFE8B31E); "mint" -> Color(0xFF4FC59A); "lavender" -> Color(0xFF9C7DF0); else -> Color(0xFF5AAEEB) }

    /** [mascot] (the school's `mascotColor`) wins for [MASCOT] only; every avatar key keeps its own colour. */
    fun body(key: String, mascot: Color?): Color = if (key == MASCOT && mascot != null) mascot else body(key)
    fun bodyDark(key: String, mascot: Color?): Color = if (key == MASCOT && mascot != null) darken(mascot) else bodyDark(key)

    /** The wings/outline shade of a body colour — the same step the four hand-picked pairs use. */
    fun darken(c: Color, factor: Float = 0.82f): Color = Color(c.red * factor, c.green * factor, c.blue * factor, c.alpha)
}

object StickerKeys {
    val all = listOf("star-badge", "rocket", "rainbow", "dino", "unicorn", "robot", "whale", "crown", "cake", "comet")
    fun emoji(key: String) = when (key) {
        "star-badge" -> "🌟"; "rocket" -> "🚀"; "rainbow" -> "🌈"; "dino" -> "🦕"; "unicorn" -> "🦄"
        "robot" -> "🤖"; "whale" -> "🐳"; "crown" -> "👑"; "cake" -> "🎂"; "comet" -> "☄️"; else -> "⭐"
    }
}
