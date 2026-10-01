package quest.ui.trace

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import quest.ui.design.BigButton
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Finger-tracing canvas. The letter is drawn as a dotted guide; the child's strokes are scored with
 * [TraceScorer] (49 samples, 30 px radius scaled by density) when they tap Done.
 */
@Composable
fun TraceCanvas(text: String, onFinished: (coverage: Float) -> Unit, modifier: Modifier = Modifier) {
    val letter = text
    val density = LocalDensity.current
    val letters = text.take(8)
    val boxHeight = 260.dp
    val boxWidth = if (letters.length <= 1) 260.dp else (letters.length * 120).dp.coerceAtMost(360.dp)
    val boxPx = with(density) { boxHeight.toPx() }
    val widthPx = with(density) { boxWidth.toPx() }
    val paddingPx = with(density) { 36.dp.toPx() }
    val strokes = remember(text, boxPx) { LetterPaths.scaledWord(letters, widthPx, boxPx, paddingPx) }
    val drawn = remember(letter) { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember(letter) { mutableStateOf<List<Offset>>(emptyList()) }
    val strokeColor = MaterialTheme.colorScheme.primary
    val guideColor = DashboardTokens.inkSoft.copy(alpha = 0.55f)
    val startColor = DashboardTokens.success
    val labels = quest.ui.stops.LocalStopLabels.current
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(2f, 22f), 0f) }

    Column(modifier, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s16)) {
        Canvas(
            Modifier.size(boxWidth, boxHeight).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(DashboardTokens.radiusMd)).border(1.dp, DashboardTokens.ruleControl, RoundedCornerShape(DashboardTokens.radiusMd))
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
                drawPath(path, guideColor, style = Stroke(guideWidth, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = dash))
                // start dot
                s.firstOrNull()?.let { drawCircle(startColor, 9.dp.toPx(), Offset(it.x, it.y)) }
            }
            (drawn + listOf(currentStroke)).forEach { s ->
                if (s.size < 2) return@forEach
                val path = Path().apply { s.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, strokeColor, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            BigButton(labels.clear, onClick = { drawn.clear(); currentStroke = emptyList() }, modifier = Modifier.width(150.dp), primary = false, compact = true)
            BigButton(labels.done, onClick = {
                val pts = (drawn + listOf(currentStroke)).flatten().map { Point(it.x, it.y) }
                val radius = with(density) { TraceScorer.RADIUS_PX.dp.toPx() }
                onFinished(TraceScorer.coverage(strokes, pts, radius))
            }, modifier = Modifier.width(150.dp), compact = true)
        }
    }
}
