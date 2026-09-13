package quest.feature.practice.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import quest.core.design.BigButton
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.feature.practice.domain.LetterPaths
import quest.feature.practice.domain.Point
import quest.feature.practice.domain.TraceScorer

/**
 * Finger-tracing canvas. The letter is drawn as a dotted guide; the child's strokes are scored with
 * [TraceScorer] (49 samples, 30 px radius scaled by density) when they tap Done.
 */
@Composable
fun TraceCanvas(letter: Char, onFinished: (coverage: Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val boxSize = 260.dp
    val boxPx = with(density) { boxSize.toPx() }
    val paddingPx = with(density) { 36.dp.toPx() }
    val strokes = remember(letter, boxPx) { LetterPaths.scaled(letter, boxPx, boxPx, paddingPx) }
    val drawn = remember(letter) { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember(letter) { mutableStateOf<List<Offset>>(emptyList()) }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(2f, 22f), 0f) }

    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s16)) {
        Canvas(
            Modifier.size(boxSize).shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream, RoundedCornerShape(Dimens.radiusCard))
                .semantics { contentDescription = "Trace the letter $letter" }
                .pointerInput(letter) {
                    detectDragGestures(
                        onDragStart = { currentStroke = listOf(it) },
                        onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                        onDragEnd = { drawn += currentStroke; currentStroke = emptyList() },
                        onDragCancel = { drawn += currentStroke; currentStroke = emptyList() },
                    )
                },
        ) {
            val guideWidth = 26.dp.toPx()
            strokes.forEach { s ->
                val path = Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, Palette.inkSoft.copy(alpha = 0.55f), style = Stroke(guideWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
                // start dot
                s.firstOrNull()?.let { drawCircle(Palette.mint, 9.dp.toPx(), Offset(it.x, it.y)) }
            }
            (drawn + listOf(currentStroke)).forEach { s ->
                if (s.size < 2) return@forEach
                val path = Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, Palette.coral, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            BigButton("Clear", onClick = { drawn.clear(); currentStroke = emptyList() }, modifier = Modifier.width(150.dp), color = Palette.cream, compact = true)
            BigButton("Done", onClick = {
                val pts = (drawn + listOf(currentStroke)).flatten().map { Point(it.x, it.y) }
                val radius = with(density) { TraceScorer.RADIUS_PX.dp.toPx() }
                onFinished(TraceScorer.coverage(strokes, pts, radius))
            }, modifier = Modifier.width(150.dp), compact = true)
        }
    }
}
