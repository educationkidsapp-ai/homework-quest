package quest.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

enum class PipPose { IDLE, WAVING, THINKING, CELEBRATING, SLEEPING }

/**
 * Pip, the mascot: a round sky-blue owl-ish blob with a coral scarf, drawn procedurally so the same
 * composable renders on every platform. See docs/design.md §5.
 */
@Composable
fun Pip(pose: PipPose, size: Dp = Dimens.pipMedium, modifier: Modifier = Modifier, animated: Boolean = true, color: String = "sky") {
    val transition = rememberInfiniteTransition(label = "pip")
    val bounce by transition.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "bounce",
        animationSpec = infiniteRepeatable(tween(if (pose == PipPose.CELEBRATING) 420 else 1800, easing = LinearEasing), RepeatMode.Reverse),
    )
    val blink by transition.animateFloat(
        initialValue = 1f, targetValue = 1f, label = "blink",
        animationSpec = infiniteRepeatable(keyframes { durationMillis = 3200; 1f at 0; 1f at 2900; 0.1f at 3000; 1f at 3100 }),
    )
    val b = if (animated) bounce else 0.5f
    val eyeOpen = if (!animated) 1f else blink

    Box(modifier.size(size).semantics { contentDescription = "Pip ${pose.name.lowercase()}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val yOffset = when (pose) {
                PipPose.CELEBRATING -> -h * 0.08f * b
                PipPose.SLEEPING -> h * 0.02f * b
                else -> -h * 0.02f * b
            }
            translate(top = yOffset) { drawPip(pose, w, h, b, eyeOpen, AvatarColors.body(color), AvatarColors.bodyDark(color)) }
        }
        if (pose == PipPose.SLEEPING) {
            Text("z z", color = Palette.ink, fontSize = (size.value * 0.16f).sp, modifier = Modifier.align(Alignment.TopEnd))
        }
    }
}

private fun DrawScope.drawPip(pose: PipPose, w: Float, h: Float, b: Float, eyeOpen: Float, body: Color, bodyDark: Color) {
    val belly = Color(0xFFEAF6FF)
    val cx = w / 2
    val cy = h * 0.55f
    val r = w * 0.36f

    // wings
    val wingColor = bodyDark
    fun wing(side: Float, up: Boolean, onChin: Boolean = false) {
        val angle = when {
            onChin -> -60f * side
            up -> -140f * side
            else -> 20f * side
        }
        val pivot = Offset(cx + side * r * 0.9f, cy + r * 0.1f)
        rotate(angle, pivot) {
            drawOval(wingColor, topLeft = Offset(pivot.x - r * 0.18f, pivot.y - r * 0.1f), size = Size(r * 0.36f, r * 0.75f))
        }
    }
    val leftUp = pose == PipPose.CELEBRATING || pose == PipPose.WAVING
    val rightUp = pose == PipPose.CELEBRATING
    wing(-1f, leftUp, onChin = pose == PipPose.THINKING)
    wing(1f, rightUp)

    // body + belly
    drawCircle(body, r, Offset(cx, cy))
    drawOval(belly, topLeft = Offset(cx - r * 0.55f, cy - r * 0.15f), size = Size(r * 1.1f, r * 0.95f))

    // ear tufts
    val tuft = Path().apply {
        moveTo(cx - r * 0.75f, cy - r * 0.55f); lineTo(cx - r * 0.55f, cy - r * 1.15f); lineTo(cx - r * 0.25f, cy - r * 0.8f); close()
        moveTo(cx + r * 0.75f, cy - r * 0.55f); lineTo(cx + r * 0.55f, cy - r * 1.15f); lineTo(cx + r * 0.25f, cy - r * 0.8f); close()
    }
    drawPath(tuft, body)

    // scarf
    drawRoundRect(Palette.coral, topLeft = Offset(cx - r * 0.85f, cy + r * 0.45f), size = Size(r * 1.7f, r * 0.32f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.16f))
    drawRoundRect(Palette.coral, topLeft = Offset(cx + r * 0.25f, cy + r * 0.6f), size = Size(r * 0.3f, r * 0.55f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.12f))

    // eyes
    val eyeY = cy - r * 0.2f
    val eyeDx = r * 0.32f
    val eyeR = r * 0.2f
    val lookDx = if (pose == PipPose.THINKING) -eyeR * 0.35f else 0f
    val lookDy = if (pose == PipPose.THINKING) -eyeR * 0.35f else 0f
    listOf(-1f, 1f).forEach { s ->
        val c = Offset(cx + s * eyeDx, eyeY)
        if (pose == PipPose.SLEEPING) {
            drawArc(Palette.ink, 20f, 140f, false, topLeft = Offset(c.x - eyeR, c.y - eyeR * 0.6f), size = Size(eyeR * 2, eyeR * 1.4f), style = Stroke(r * 0.06f, cap = StrokeCap.Round))
        } else {
            drawCircle(Color.White, eyeR, c)
            drawOval(Palette.ink, topLeft = Offset(c.x - eyeR * 0.5f + lookDx, c.y - eyeR * 0.5f * eyeOpen + lookDy), size = Size(eyeR, eyeR * eyeOpen))
            drawCircle(Color.White, eyeR * 0.15f, Offset(c.x + eyeR * 0.2f + lookDx, c.y - eyeR * 0.25f + lookDy))
        }
    }

    // beak
    val beak = Path().apply {
        moveTo(cx - r * 0.12f, cy + r * 0.05f); lineTo(cx + r * 0.12f, cy + r * 0.05f); lineTo(cx, cy + r * 0.25f); close()
    }
    drawPath(beak, Palette.sun)

    // cheeks
    if (pose == PipPose.CELEBRATING || pose == PipPose.WAVING || pose == PipPose.IDLE) {
        drawCircle(Color(0x55FF8A65), r * 0.12f, Offset(cx - r * 0.55f, cy + r * 0.05f))
        drawCircle(Color(0x55FF8A65), r * 0.12f, Offset(cx + r * 0.55f, cy + r * 0.05f))
    }

    // celebration sparkles
    if (pose == PipPose.CELEBRATING) {
        val sparkle = Palette.sun
        listOf(Offset(cx - r * 1.3f, cy - r * 0.9f), Offset(cx + r * 1.3f, cy - r * 1.0f), Offset(cx, cy - r * 1.5f)).forEachIndexed { i, o ->
            val s = r * (0.12f + 0.06f * b)
            drawLine(sparkle, Offset(o.x - s, o.y), Offset(o.x + s, o.y), strokeWidth = r * 0.05f, cap = StrokeCap.Round)
            drawLine(sparkle, Offset(o.x, o.y - s), Offset(o.x, o.y + s), strokeWidth = r * 0.05f, cap = StrokeCap.Round)
        }
    }
}
