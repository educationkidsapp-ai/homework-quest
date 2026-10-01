package quest.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The read-aloud button present on every student screen: a 64 dp target (§7) drawn as a bordered square in the
 * school's accent, the same control language as the student home.
 */
@Composable
fun ReadAloudButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Read aloud") {
    FormalIconButton(onClick, contentDescription, modifier) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String = "Back") {
    FormalIconButton(onClick, contentDescription, modifier) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = DashboardTokens.ink)
    }
}

@Composable
private fun FormalIconButton(onClick: () -> Unit, contentDescription: String, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(DashboardTokens.radiusMd)
    Box(
        modifier.size(Dimens.minTarget).background(MaterialTheme.colorScheme.surface, shape).border(1.dp, DashboardTokens.ruleControl, shape).clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * A 176×100 answer option. `dimmed` is the post-wrong-answer state: visibly out of play, not clickable,
 * and never red.
 */
@Composable
fun AnswerTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surface,
    fontSize: Int = 28,
    dimmedDescription: String = "already tried",
    content: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusMd)
    Box(
        modifier
            .width(Dimens.tileWidth).height(Dimens.tileHeight)
            .alpha(if (dimmed) 0.35f else 1f)
            .background(color, shape)
            .border(1.dp, DashboardTokens.ruleControl, shape)
            .clip(shape)
            .clickable(enabled = enabled && !dimmed, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (dimmed) "$label, $dimmedDescription" else label },
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content() else {
            Text(label, fontSize = fontSize.sp, color = DashboardTokens.inkStrong, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/**
 * The student call to action, ≥ 64 dp tall (§7). Primary is filled with the school's accent; secondary is the
 * bordered surface button of the student home.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(DashboardTokens.radiusSm)
    Box(
        modifier.heightIn(min = Dimens.minTarget).fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, shape)
            .then(if (primary) Modifier else Modifier.border(1.dp, DashboardTokens.ruleControl, shape))
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (compact) Dimens.s12 else Dimens.s24, vertical = Dimens.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = if (compact) 15.sp else 17.sp),
            color = if (primary) MaterialTheme.colorScheme.onPrimary else DashboardTokens.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Progress within a set: N stars filling up. Stars, never a number or a percentage (§7). */
@Composable
fun StarRow(total: Int, filled: Int, modifier: Modifier = Modifier, starSize: Dp = 28.dp) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val on = i < filled
            Text(
                if (on) "★" else "☆",
                fontSize = (starSize.value).sp,
                color = if (on) DashboardTokens.warning else DashboardTokens.inkLight,
                modifier = Modifier.semantics { contentDescription = if (on) "star earned" else "star" },
            )
        }
    }
}
