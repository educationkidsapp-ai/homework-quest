package quest.ui.stops

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.Palette

/** A drawing is stored as normalised strokes (0..1) so it re-renders at any size, on any platform. */
@Serializable
data class DrawnStroke(val color: Int, val points: List<Float>)

object Drawings {
    private val json = Json
    fun encode(strokes: List<DrawnStroke>): String = json.encodeToString(ListSerializer(DrawnStroke.serializer()), strokes)
    fun decode(text: String): List<DrawnStroke> = runCatching { json.decodeFromString(ListSerializer(DrawnStroke.serializer()), text) }.getOrDefault(emptyList())
    val colours = listOf(Palette.ink, Palette.coral, Palette.sun, Palette.mint, Palette.sea, Palette.lavender)
}

/** Finger drawing with six colours; `onChange` receives the strokes JSON after every stroke. */
@Composable
fun DrawingCanvas(onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var colour by remember { mutableStateOf(0) }
    var size by remember { mutableStateOf(Offset(1f, 1f)) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = Dimens.s8)) {
            Drawings.colours.forEachIndexed { i, c ->
                Box(
                    Modifier.width(44.dp).aspectRatio(1f).shadow(if (i == colour) 6.dp else 1.dp, RoundedCornerShape(50)).background(c, RoundedCornerShape(50))
                        .clickable { colour = i }.semantics { contentDescription = "colour $i" + if (i == colour) ", selected" else "" },
                )
            }
        }
        Canvas(
            Modifier.fillMaxWidth().padding(horizontal = Dimens.s16).aspectRatio(1f).shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Color.White, RoundedCornerShape(Dimens.radiusCard))
                .clipToBounds().semantics { contentDescription = "drawing canvas, ${strokes.size} strokes" }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> current = current + change.position },
                        onDragEnd = {
                            if (current.size > 1) { strokes += DrawnStroke(colour, current.flatMap { listOf((it.x / size.x).coerceIn(0f, 1f), (it.y / size.y).coerceIn(0f, 1f)) }); onChange(Drawings.encode(strokes)) }
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    )
                },
        ) {
            size = Offset(this.size.width, this.size.height)
            drawStrokes(strokes, this.size.width, this.size.height)
            if (current.size > 1) drawPath(Path().apply { current.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, Drawings.colours[colour], style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        BigButton("Clear", onClick = { strokes.clear(); onChange(Drawings.encode(strokes)) }, modifier = Modifier.width(150.dp).padding(top = Dimens.s8), color = Palette.cream, compact = true)
    }
}

/** Re-renders a saved drawing (parent panel). */
@Composable
fun DrawingPreview(strokesJson: String, modifier: Modifier = Modifier) {
    val strokes = remember(strokesJson) { Drawings.decode(strokesJson) }
    Canvas(modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.White, RoundedCornerShape(16.dp)).semantics { contentDescription = "drawing with ${strokes.size} strokes" }) {
        drawStrokes(strokes, size.width, size.height)
    }
}

private fun DrawScope.drawStrokes(strokes: List<DrawnStroke>, w: Float, h: Float) {
    strokes.forEach { s ->
        val path = Path()
        s.points.chunked(2).forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
        drawPath(path, Drawings.colours.getOrElse(s.color) { Palette.ink }, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

