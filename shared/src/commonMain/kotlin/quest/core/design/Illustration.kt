package quest.core.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The fixed illustration set (docs/design.md §9), keyed by the same keys as `quest.api.Illustrations`.
 * v1 draws each as a coloured glyph card; replacing a glyph with real art is a one-line change here.
 */
object IllustrationGlyphs {
    private val glyphs: Map<String, Pair<String, Color>> = mapOf(
        "ship" to ("🚢" to Color(0xFFD6ECFF)), "sheep" to ("🐑" to Color(0xFFEFF7E6)), "shop" to ("🏪" to Color(0xFFFFE8D6)),
        "shell" to ("🐚" to Color(0xFFFFEFF4)), "shoe" to ("👟" to Color(0xFFE8F0FF)), "fish" to ("🐟" to Color(0xFFD6F2FF)),
        "chair" to ("🪑" to Color(0xFFFFF0D6)), "cheese" to ("🧀" to Color(0xFFFFF7CC)), "chick" to ("🐤" to Color(0xFFFFFBD6)),
        "chips" to ("🍟" to Color(0xFFFFF1D1)), "thumb" to ("👍" to Color(0xFFFFE6D9)), "three" to ("3️⃣" to Color(0xFFE6F0FF)),
        "bath" to ("🛁" to Color(0xFFE0F5FF)), "moth" to ("🦋" to Color(0xFFF1E8FF)), "sun" to ("☀️" to Color(0xFFFFF4CC)),
        "sock" to ("🧦" to Color(0xFFE8F8F0)), "cat" to ("🐱" to Color(0xFFFFEEDD)), "dog" to ("🐶" to Color(0xFFF3EBDD)),
        "hat" to ("🎩" to Color(0xFFECECF5)), "bed" to ("🛏️" to Color(0xFFE8ECFF)), "cup" to ("🥤" to Color(0xFFFFEAF0)),
        "pen" to ("🖊️" to Color(0xFFE6F3FF)), "pig" to ("🐷" to Color(0xFFFFE4EC)), "bus" to ("🚌" to Color(0xFFFFF3CC)),
        "fox" to ("🦊" to Color(0xFFFFE6CC)), "apple" to ("🍎" to Color(0xFFFFE3E3)), "ball" to ("⚽" to Color(0xFFEDEDED)),
        "tree" to ("🌳" to Color(0xFFE2F5E1)), "bee" to ("🐝" to Color(0xFFFFF6CC)), "moon" to ("🌙" to Color(0xFFE6E8FF)),
        "star" to ("⭐" to Color(0xFFFFF6CC)), "car" to ("🚗" to Color(0xFFE6F0FF)),
    )

    fun glyph(key: String): String = glyphs[key]?.first ?: "❓"
    fun tint(key: String): Color = glyphs[key]?.second ?: Palette.cream
    val keys: Set<String> get() = glyphs.keys
}

@Composable
fun Illustration(key: String, size: Dp = 120.dp, modifier: Modifier = Modifier, corner: Dp = Dimens.radiusCard) {
    Box(
        modifier.size(size).background(IllustrationGlyphs.tint(key), RoundedCornerShape(corner))
            .semantics { contentDescription = key },
        contentAlignment = Alignment.Center,
    ) {
        Text(IllustrationGlyphs.glyph(key), fontSize = (size.value * 0.55f).sp)
    }
}
