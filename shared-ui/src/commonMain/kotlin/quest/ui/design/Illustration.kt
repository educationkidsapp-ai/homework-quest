package quest.ui.design

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
import quest.api.Illustrations

/**
 * The fixed illustration set (keys in `quest.api.Illustrations`). v1 draws each as a tinted glyph card;
 * replacing a glyph with real art is a one-line change here. A test asserts every key has a glyph.
 */
object IllustrationGlyphs {
    private val tints = listOf(Color(0xFFD6ECFF), Color(0xFFEFF7E6), Color(0xFFFFE8D6), Color(0xFFFFEFF4), Color(0xFFFFF7CC), Color(0xFFF1E8FF), Color(0xFFE0F5FF))
    private val glyphs: Map<String, String> = mapOf(
        "ship" to "🚢", "sheep" to "🐑", "shop" to "🏪", "shell" to "🐚", "shoe" to "👟", "fish" to "🐟", "chair" to "🪑", "cheese" to "🧀", "chick" to "🐤", "chips" to "🍟",
        "thumb" to "👍", "three" to "3️⃣", "bath" to "🛁", "moth" to "🦋", "sun" to "☀️", "sock" to "🧦", "cat" to "🐱", "dog" to "🐶", "hat" to "🎩", "bed" to "🛏️",
        "cup" to "🥤", "pen" to "🖊️", "pig" to "🐷", "bus" to "🚌", "fox" to "🦊", "apple" to "🍎", "ball" to "⚽", "tree" to "🌳", "bee" to "🐝", "moon" to "🌙",
        "star" to "⭐", "car" to "🚗", "carrot" to "🥕", "potato" to "🥔", "onion" to "🧅", "tomato" to "🍅", "pea" to "🫛", "corn" to "🌽", "soup" to "🍲", "pot" to "🍯",
        "spoon" to "🥄", "bowl" to "🥣", "fridge" to "🧊", "mummy" to "👩", "daddy" to "👨", "boy" to "👦", "girl" to "👧", "house" to "🏠", "kitchen" to "🍳", "hike" to "🥾",
        "mountain" to "⛰️", "backpack" to "🎒", "water" to "💧", "bread" to "🍞", "egg" to "🥚", "milk" to "🥛", "leaf" to "🍃", "flower" to "🌸", "rain" to "🌧️", "cloud" to "☁️",
        "boat" to "⛵", "bike" to "🚲", "book" to "📖", "pencil" to "✏️", "school" to "🏫", "bag" to "👜", "door" to "🚪", "window" to "🪟", "hand" to "✋", "foot" to "🦶",
        "eye" to "👁️", "ear" to "👂", "nose" to "👃", "mouth" to "👄", "heart" to "❤️", "gift" to "🎁", "cake" to "🎂", "balloon" to "🎈", "kite" to "🪁", "drum" to "🥁",
        "bell" to "🔔", "key" to "🔑", "lock" to "🔒", "clock" to "⏰", "map" to "🗺️", "flag" to "🚩", "ladder" to "🪜", "rope" to "🪢", "tent" to "⛺", "fire" to "🔥",
        "ice" to "🧊", "snow" to "❄️", "wind" to "💨",
    )

    fun glyph(key: String): String = glyphs[key] ?: "❓"
    fun tint(key: String): Color = tints[(key.hashCode() and 0x7fffffff) % tints.size]
    val keys: Set<String> get() = glyphs.keys
    fun missingKeys(): List<String> = Illustrations.keys.filter { it !in glyphs }
}

@Composable
fun Illustration(key: String, size: Dp = 120.dp, modifier: Modifier = Modifier, corner: Dp = Dimens.radiusCard) {
    Box(
        modifier.size(size).background(IllustrationGlyphs.tint(key), RoundedCornerShape(corner)).semantics { contentDescription = key },
        contentAlignment = Alignment.Center,
    ) { Text(IllustrationGlyphs.glyph(key), fontSize = (size.value * 0.55f).sp) }
}
