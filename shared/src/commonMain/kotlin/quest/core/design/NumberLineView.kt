package quest.core.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.NumberLine

/** The number line used on the hint sheet for numeric question types; highlighted ticks get jump arcs. */
@Composable
fun NumberLineView(line: NumberLine, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.ink)
    Canvas(
        modifier.fillMaxWidth().height(96.dp)
            .semantics { contentDescription = "Number line from ${line.from} to ${line.to}" },
    ) {
        val count = ((line.to - line.from) / line.step) + 1
        if (count < 2) return@Canvas
        val padding = 24.dp.toPx()
        val y = size.height * 0.65f
        val gap = (size.width - 2 * padding) / (count - 1)
        drawLine(Palette.seaDeep, Offset(padding - 8.dp.toPx(), y), Offset(size.width - padding + 8.dp.toPx(), y), 4.dp.toPx(), StrokeCap.Round)
        val highlighted = line.highlight.toSet()
        for (i in 0 until count) {
            val v = line.from + i * line.step
            val x = padding + i * gap
            val on = v in highlighted
            drawLine(Palette.seaDeep, Offset(x, y - 8.dp.toPx()), Offset(x, y + 8.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
            if (on) drawCircle(Palette.sun, 10.dp.toPx(), Offset(x, y))
            val layout = measurer.measure(v.toString(), style)
            drawText(layout, topLeft = Offset(x - layout.size.width / 2f, y + 12.dp.toPx()))
        }
        // jump arcs between consecutive highlighted values
        val sorted = line.highlight.sorted()
        for (k in 0 until sorted.size - 1) {
            val a = sorted[k]; val b = sorted[k + 1]
            val xa = padding + ((a - line.from) / line.step.toFloat()) * gap
            val xb = padding + ((b - line.from) / line.step.toFloat()) * gap
            val path = Path().apply {
                moveTo(xa, y - 8.dp.toPx())
                quadraticTo((xa + xb) / 2, y - 44.dp.toPx(), xb, y - 8.dp.toPx())
            }
            drawPath(path, Palette.coral, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}
