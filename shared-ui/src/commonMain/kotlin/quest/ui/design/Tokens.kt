package quest.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Every visual token from docs/design.md lives here. Swap this file to re-skin the app. */
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

    // Parent mode + admin panel: flat Modernist — Archivo, square corners, one red accent.
    val parentBg = Color(0xFFF5F4F0)
    val parentSurface = Color(0xFFFFFFFF)
    val parentInk = Color(0xFF111111)
    val parentInkSoft = Color(0xFF6B6B6B)
    val parentAccent = Color(0xFFD7263D)
    val parentAccentSoft = Color(0xFFFBE4E7)
    val parentLine = Color(0xFF111111)
    val parentRule = Color(0xFFD9D7D0)

    /** Progress bands, never red. */
    val bandGood = mint
    val bandMid = sun
    val bandLook = coral
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
    val radiusParent = 0.dp   // square corners in parent mode / admin
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
    fun body(key: String): Color = when (key) { "sun" -> Color(0xFFFFD35C); "mint" -> Color(0xFF7EE0BA); "lavender" -> Color(0xFFC3ADFF); else -> Color(0xFF7EC8FF) }
    fun bodyDark(key: String): Color = when (key) { "sun" -> Color(0xFFE8B31E); "mint" -> Color(0xFF4FC59A); "lavender" -> Color(0xFF9C7DF0); else -> Color(0xFF5AAEEB) }
}

object StickerKeys {
    val all = listOf("star-badge", "rocket", "rainbow", "dino", "unicorn", "robot", "whale", "crown", "cake", "comet")
    fun emoji(key: String) = when (key) {
        "star-badge" -> "🌟"; "rocket" -> "🚀"; "rainbow" -> "🌈"; "dino" -> "🦕"; "unicorn" -> "🦄"
        "robot" -> "🤖"; "whale" -> "🐳"; "crown" -> "👑"; "cake" -> "🎂"; "comet" -> "☄️"; else -> "⭐"
    }
}
