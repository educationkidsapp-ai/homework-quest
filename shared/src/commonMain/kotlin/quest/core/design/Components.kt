package quest.core.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The 64 dp read-aloud button present on every child screen. */
@Composable
fun ReadAloudButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(Dimens.readAloud).shadow(4.dp, CircleShape).background(Palette.sun, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Read aloud" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Palette.ink, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun RoundIconButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier = Modifier, color: Color = Palette.cream, content: @Composable () -> Unit) {
    Box(
        modifier.size(Dimens.minTarget).shadow(2.dp, CircleShape).background(color, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier) =
    RoundIconButton(onClick, "Back", modifier) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Palette.ink) }

/**
 * A 176×100 answer tile. `dimmed` is the post-wrong-answer state: visibly out of play, not clickable,
 * and never red.
 */
@Composable
fun AnswerTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    enabled: Boolean = true,
    color: Color = Palette.cream,
    fontSize: Int = 36,
    content: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, spring(), label = "tile")
    Box(
        modifier
            .width(Dimens.tileWidth).height(Dimens.tileHeight)
            .scale(scale)
            .alpha(if (dimmed) 0.35f else 1f)
            .shadow(if (dimmed) 0.dp else 6.dp, RoundedCornerShape(Dimens.radiusTile))
            .background(color, RoundedCornerShape(Dimens.radiusTile))
            .border(3.dp, if (dimmed) Palette.inkSoft.copy(alpha = 0.3f) else Palette.sunDeep.copy(alpha = 0.35f), RoundedCornerShape(Dimens.radiusTile))
            .clickable(enabled = enabled && !dimmed, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (dimmed) "$label, already tried" else label },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else {
            Text(label, fontSize = fontSize.sp, color = Palette.ink, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Big rounded child CTA, ≥ 64 dp tall. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.sun,
    textColor: Color = Palette.ink,
    emoji: String? = null,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    Box(
        modifier.heightIn(min = Dimens.minTarget).fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .shadow(6.dp, RoundedCornerShape(Dimens.radiusTile))
            .background(color, RoundedCornerShape(Dimens.radiusTile))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) Dimens.s12 else Dimens.s24, vertical = Dimens.s16),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (emoji != null) { Text(emoji, fontSize = if (compact) 22.sp else 26.sp); Spacer(Modifier.width(Dimens.s8)) }
            Text(text, style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleLarge, color = textColor, maxLines = 1, softWrap = false)
        }
    }
}

/** Progress within a set: N stars filling up. */
@Composable
fun StarRow(total: Int, filled: Int, modifier: Modifier = Modifier, starSize: Dp = 28.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val on = i < filled
            val scale by animateFloatAsState(if (on) 1f else 0.8f, spring(dampingRatio = 0.5f), label = "star")
            Text(
                if (on) "⭐" else "☆",
                fontSize = (starSize.value).sp,
                color = if (on) Palette.sunDeep else Palette.inkSoft.copy(alpha = 0.5f),
                modifier = Modifier.scale(scale).semantics { contentDescription = if (on) "star earned" else "star" },
            )
        }
    }
}

@Composable
fun SpeechBubble(text: String, modifier: Modifier = Modifier, color: Color = Palette.cream) {
    Box(
        modifier.shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(color, RoundedCornerShape(Dimens.radiusCard))
            .padding(horizontal = Dimens.s24, vertical = Dimens.s16),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
    }
}

@Composable
fun ChildCard(modifier: Modifier = Modifier, color: Color = Palette.cream, content: @Composable () -> Unit) {
    Column(
        modifier.shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(color, RoundedCornerShape(Dimens.radiusCard)).padding(Dimens.s16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
