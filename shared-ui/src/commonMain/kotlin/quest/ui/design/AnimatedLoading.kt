package quest.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern animated loading indicator with three staggered pulsating dots.
 * Creates a fluid wave effect that matches the Dashboard design system.
 */
@Composable
fun AnimatedDotsLoader(
    modifier: Modifier = Modifier,
    dotSize: Dp = 10.dp,
    spacing: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "dots_loader")

    val dot1Scale by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot1_scale",
    )
    val dot2Scale by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot2_scale",
    )
    val dot3Scale by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, delayMillis = 400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot3_scale",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .scale(dot1Scale)
                .alpha(dot1Scale.coerceIn(0.35f, 1f))
                .clip(CircleShape)
                .background(color),
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .scale(dot2Scale)
                .alpha(dot2Scale.coerceIn(0.35f, 1f))
                .clip(CircleShape)
                .background(color),
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .scale(dot3Scale)
                .alpha(dot3Scale.coerceIn(0.35f, 1f))
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Compact circular pulsating pulse for inline loading (buttons, icons, chips).
 */
@Composable
fun CompactAnimatedLoading(
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "compact_pulse")
    val scale by transition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 0.5f)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Full-screen or container animated loading view with breathing mascot and wave dots.
 */
@Composable
fun AnimatedLoadingView(
    text: String = "Loading…",
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "loading_view_anim")
    val mascotScale by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mascot_scale",
    )

    val textColor = DashboardTokens.inkStrong

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .scale(mascotScale)
                .size(80.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✨",
                fontSize = 36.sp,
            )
        }

        Spacer(Modifier.height(24.dp))

        AnimatedDotsLoader(
            dotSize = 12.dp,
            spacing = 8.dp,
            color = DashboardTokens.accentInk,
        )

        Spacer(Modifier.height(18.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}
