package quest.feature.practice.domain

import kotlin.math.sqrt

data class Point(val x: Float, val y: Float)

/**
 * Trace scoring (dev prompt §6): sample the letter path into 49 points, count points with a drawn point
 * within 30 px, map coverage to stars at 0.35 / 0.6 / 0.8.
 */
object TraceScorer {
    const val SAMPLES = 49
    const val RADIUS_PX = 30f

    fun coverage(letterStrokes: List<List<Point>>, drawn: List<Point>, radiusPx: Float = RADIUS_PX): Float {
        if (drawn.isEmpty()) return 0f
        val samples = sample(letterStrokes, SAMPLES)
        if (samples.isEmpty()) return 0f
        val r2 = radiusPx * radiusPx
        val covered = samples.count { s -> drawn.any { d -> dist2(s, d) <= r2 } }
        return covered.toFloat() / samples.size
    }

    fun stars(coverage: Float): Int = when {
        coverage >= 0.8f -> 3
        coverage >= 0.6f -> 2
        coverage >= 0.35f -> 1
        else -> 0
    }

    fun score(letterStrokes: List<List<Point>>, drawn: List<Point>, radiusPx: Float = RADIUS_PX): Int = stars(coverage(letterStrokes, drawn, radiusPx))

    /** Evenly spaced points along the polyline strokes by arc length. */
    fun sample(strokes: List<List<Point>>, count: Int): List<Point> {
        val segments = mutableListOf<Pair<Point, Point>>()
        strokes.forEach { s -> for (i in 0 until s.size - 1) segments += s[i] to s[i + 1] }
        if (segments.isEmpty()) return strokes.flatten().take(count)
        val lengths = segments.map { (a, b) -> sqrt(dist2(a, b)) }
        val total = lengths.sum()
        if (total == 0f) return List(count) { segments.first().first }
        val out = ArrayList<Point>(count)
        for (k in 0 until count) {
            val target = total * k / (count - 1).coerceAtLeast(1)
            var acc = 0f
            var placed = false
            for (i in segments.indices) {
                val len = lengths[i]
                if (acc + len >= target || i == segments.lastIndex) {
                    val t = if (len == 0f) 0f else ((target - acc) / len).coerceIn(0f, 1f)
                    val (a, b) = segments[i]
                    out += Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                    placed = true
                    break
                }
                acc += len
            }
            if (!placed) out += segments.last().second
        }
        return out
    }

    private fun dist2(a: Point, b: Point): Float { val dx = a.x - b.x; val dy = a.y - b.y; return dx * dx + dy * dy }
}
