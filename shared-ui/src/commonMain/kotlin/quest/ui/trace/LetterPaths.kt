package quest.ui.trace

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stroke geometry for tracing, in a unit box (0..1, y down). Uppercase letters use the full box,
 * lowercase letters use the x-height band 0.35..1 with ascenders/descenders where needed.
 */
object LetterPaths {
    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, fromDeg: Float, toDeg: Float, steps: Int = 16): List<Point> =
        (0..steps).map { i ->
            val a = (fromDeg + (toDeg - fromDeg) * i / steps) * PI.toFloat() / 180f
            Point(cx + rx * cos(a), cy + ry * sin(a))
        }

    private fun line(vararg pts: Pair<Float, Float>) = pts.map { Point(it.first, it.second) }

    private val upper: Map<Char, List<List<Point>>> = mapOf(
        'A' to listOf(line(0.15f to 0.95f, 0.5f to 0.05f, 0.85f to 0.95f), line(0.3f to 0.62f, 0.7f to 0.62f)),
        'B' to listOf(line(0.25f to 0.05f, 0.25f to 0.95f), line(0.25f to 0.05f) + arc(0.5f, 0.27f, 0.28f, 0.22f, -90f, 90f) + line(0.25f to 0.5f), line(0.25f to 0.5f) + arc(0.5f, 0.72f, 0.32f, 0.23f, -90f, 90f) + line(0.25f to 0.95f)),
        'C' to listOf(arc(0.5f, 0.5f, 0.35f, 0.45f, -40f, -320f)),
        'D' to listOf(line(0.25f to 0.05f, 0.25f to 0.95f), line(0.25f to 0.05f) + arc(0.4f, 0.5f, 0.42f, 0.45f, -90f, 90f) + line(0.25f to 0.95f)),
        'E' to listOf(line(0.75f to 0.05f, 0.25f to 0.05f, 0.25f to 0.95f, 0.75f to 0.95f), line(0.25f to 0.5f, 0.65f to 0.5f)),
        'F' to listOf(line(0.75f to 0.05f, 0.25f to 0.05f, 0.25f to 0.95f), line(0.25f to 0.5f, 0.65f to 0.5f)),
        'G' to listOf(arc(0.5f, 0.5f, 0.35f, 0.45f, -40f, -320f) + line(0.85f to 0.55f, 0.55f to 0.55f)),
        'H' to listOf(line(0.2f to 0.05f, 0.2f to 0.95f), line(0.8f to 0.05f, 0.8f to 0.95f), line(0.2f to 0.5f, 0.8f to 0.5f)),
        'I' to listOf(line(0.5f to 0.05f, 0.5f to 0.95f), line(0.3f to 0.05f, 0.7f to 0.05f), line(0.3f to 0.95f, 0.7f to 0.95f)),
        'J' to listOf(line(0.65f to 0.05f, 0.65f to 0.7f) + arc(0.45f, 0.7f, 0.2f, 0.25f, 0f, 180f)),
        'K' to listOf(line(0.25f to 0.05f, 0.25f to 0.95f), line(0.75f to 0.05f, 0.25f to 0.55f, 0.75f to 0.95f)),
        'L' to listOf(line(0.25f to 0.05f, 0.25f to 0.95f, 0.8f to 0.95f)),
        'M' to listOf(line(0.15f to 0.95f, 0.15f to 0.05f, 0.5f to 0.6f, 0.85f to 0.05f, 0.85f to 0.95f)),
        'N' to listOf(line(0.2f to 0.95f, 0.2f to 0.05f, 0.8f to 0.95f, 0.8f to 0.05f)),
        'O' to listOf(arc(0.5f, 0.5f, 0.35f, 0.45f, -90f, 270f)),
        'P' to listOf(line(0.25f to 0.95f, 0.25f to 0.05f) + arc(0.5f, 0.3f, 0.3f, 0.25f, -90f, 90f) + line(0.25f to 0.55f)),
        'Q' to listOf(arc(0.5f, 0.5f, 0.35f, 0.45f, -90f, 270f), line(0.6f to 0.7f, 0.85f to 0.98f)),
        'R' to listOf(line(0.25f to 0.95f, 0.25f to 0.05f) + arc(0.5f, 0.3f, 0.3f, 0.25f, -90f, 90f) + line(0.25f to 0.55f), line(0.45f to 0.55f, 0.8f to 0.95f)),
        'S' to listOf(arc(0.5f, 0.28f, 0.3f, 0.23f, -20f, -270f) + arc(0.5f, 0.72f, 0.3f, 0.23f, -90f, 160f)),
        'T' to listOf(line(0.15f to 0.05f, 0.85f to 0.05f), line(0.5f to 0.05f, 0.5f to 0.95f)),
        'U' to listOf(line(0.2f to 0.05f, 0.2f to 0.6f) + arc(0.5f, 0.6f, 0.3f, 0.35f, 180f, 360f) + line(0.8f to 0.05f)),
        'V' to listOf(line(0.15f to 0.05f, 0.5f to 0.95f, 0.85f to 0.05f)),
        'W' to listOf(line(0.1f to 0.05f, 0.3f to 0.95f, 0.5f to 0.35f, 0.7f to 0.95f, 0.9f to 0.05f)),
        'X' to listOf(line(0.2f to 0.05f, 0.8f to 0.95f), line(0.8f to 0.05f, 0.2f to 0.95f)),
        'Y' to listOf(line(0.2f to 0.05f, 0.5f to 0.5f, 0.8f to 0.05f), line(0.5f to 0.5f, 0.5f to 0.95f)),
        'Z' to listOf(line(0.2f to 0.05f, 0.8f to 0.05f, 0.2f to 0.95f, 0.8f to 0.95f)),
    )

    private val lower: Map<Char, List<List<Point>>> = mapOf(
        'a' to listOf(arc(0.45f, 0.7f, 0.25f, 0.25f, -30f, -330f), line(0.7f to 0.45f, 0.7f to 0.95f)),
        'b' to listOf(line(0.3f to 0.05f, 0.3f to 0.95f), arc(0.52f, 0.7f, 0.24f, 0.25f, 180f, 540f)),
        'c' to listOf(arc(0.5f, 0.7f, 0.25f, 0.25f, -40f, -320f)),
        'd' to listOf(line(0.7f to 0.05f, 0.7f to 0.95f), arc(0.48f, 0.7f, 0.24f, 0.25f, 0f, 360f)),
        'e' to listOf(line(0.25f to 0.7f, 0.75f to 0.7f) + arc(0.5f, 0.7f, 0.25f, 0.25f, 0f, -300f)),
        'f' to listOf(arc(0.55f, 0.2f, 0.2f, 0.15f, 180f, 270f) + line(0.35f to 0.2f, 0.35f to 0.95f), line(0.2f to 0.45f, 0.55f to 0.45f)),
        'g' to listOf(arc(0.48f, 0.65f, 0.24f, 0.22f, 0f, 360f), line(0.72f to 0.45f, 0.72f to 0.9f) + arc(0.5f, 0.9f, 0.22f, 0.15f, 0f, 150f)),
        'h' to listOf(line(0.3f to 0.05f, 0.3f to 0.95f), arc(0.5f, 0.65f, 0.2f, 0.2f, 180f, 360f) + line(0.7f to 0.65f, 0.7f to 0.95f)),
        'i' to listOf(line(0.5f to 0.45f, 0.5f to 0.95f), line(0.5f to 0.25f, 0.5f to 0.3f)),
        'j' to listOf(line(0.55f to 0.45f, 0.55f to 0.9f) + arc(0.4f, 0.9f, 0.15f, 0.12f, 0f, 150f), line(0.55f to 0.25f, 0.55f to 0.3f)),
        'k' to listOf(line(0.3f to 0.05f, 0.3f to 0.95f), line(0.7f to 0.45f, 0.3f to 0.72f, 0.7f to 0.95f)),
        'l' to listOf(line(0.5f to 0.05f, 0.5f to 0.95f)),
        'm' to listOf(line(0.2f to 0.95f, 0.2f to 0.45f), arc(0.35f, 0.6f, 0.15f, 0.15f, 180f, 360f) + line(0.5f to 0.6f, 0.5f to 0.95f), arc(0.65f, 0.6f, 0.15f, 0.15f, 180f, 360f) + line(0.8f to 0.6f, 0.8f to 0.95f)),
        'n' to listOf(line(0.3f to 0.95f, 0.3f to 0.45f), arc(0.5f, 0.65f, 0.2f, 0.2f, 180f, 360f) + line(0.7f to 0.65f, 0.7f to 0.95f)),
        'o' to listOf(arc(0.5f, 0.7f, 0.25f, 0.25f, -90f, 270f)),
        'p' to listOf(line(0.3f to 0.45f, 0.3f to 1.0f), arc(0.52f, 0.7f, 0.24f, 0.25f, 180f, 540f)),
        'q' to listOf(arc(0.48f, 0.7f, 0.24f, 0.25f, 0f, 360f), line(0.72f to 0.45f, 0.72f to 1.0f)),
        'r' to listOf(line(0.35f to 0.95f, 0.35f to 0.45f), arc(0.55f, 0.65f, 0.2f, 0.2f, 180f, 300f)),
        's' to listOf(arc(0.5f, 0.58f, 0.2f, 0.13f, -20f, -270f) + arc(0.5f, 0.82f, 0.2f, 0.13f, -90f, 160f)),
        't' to listOf(line(0.45f to 0.15f, 0.45f to 0.85f) + arc(0.6f, 0.85f, 0.15f, 0.1f, 180f, 90f), line(0.25f to 0.45f, 0.7f to 0.45f)),
        'u' to listOf(line(0.3f to 0.45f, 0.3f to 0.75f) + arc(0.5f, 0.75f, 0.2f, 0.2f, 180f, 360f), line(0.7f to 0.45f, 0.7f to 0.95f)),
        'v' to listOf(line(0.25f to 0.45f, 0.5f to 0.95f, 0.75f to 0.45f)),
        'w' to listOf(line(0.15f to 0.45f, 0.32f to 0.95f, 0.5f to 0.6f, 0.68f to 0.95f, 0.85f to 0.45f)),
        'x' to listOf(line(0.25f to 0.45f, 0.75f to 0.95f), line(0.75f to 0.45f, 0.25f to 0.95f)),
        'y' to listOf(line(0.25f to 0.45f, 0.5f to 0.95f), line(0.75f to 0.45f, 0.35f to 1.0f)),
        'z' to listOf(line(0.25f to 0.45f, 0.75f to 0.45f, 0.25f to 0.95f, 0.75f to 0.95f)),
    )

    fun strokes(letter: Char): List<List<Point>> = upper[letter] ?: lower[letter] ?: upper[letter.uppercaseChar()] ?: listOf(line(0.5f to 0.05f, 0.5f to 0.95f))

    /** Lays a short word out left to right, each letter in an equal cell, for tracing whole words. */
    fun scaledWord(word: String, width: Float, height: Float, padding: Float = 0f): List<List<Point>> {
        if (word.length <= 1) return scaled(word.firstOrNull() ?: 'l', width, height, padding)
        val cell = (width - 2 * padding) / word.length
        return word.flatMapIndexed { i, c ->
            strokes(c).map { s -> s.map { Point(padding + i * cell + it.x * cell * 0.9f + cell * 0.05f, padding + it.y * (height - 2 * padding)) } }
        }
    }

    /** Scales unit strokes into a box of the given size with padding. */
    fun scaled(letter: Char, width: Float, height: Float, padding: Float = 0f): List<List<Point>> {
        val w = width - 2 * padding
        val h = height - 2 * padding
        return strokes(letter).map { s -> s.map { Point(padding + it.x * w, padding + it.y * h) } }
    }
}
