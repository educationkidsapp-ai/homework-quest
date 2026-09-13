package quest.feature.practice

import quest.feature.practice.domain.LetterPaths
import quest.feature.practice.domain.Point
import quest.feature.practice.domain.TraceScorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TraceScorerTest {
    private val box = 300f
    private val letter = LetterPaths.scaled('T', box, box)

    @Test fun samplesExactly49Points() { assertEquals(49, TraceScorer.sample(letter, 49).size) }

    @Test fun perfectTraceIsThreeStars() {
        val drawn = TraceScorer.sample(letter, 200)
        assertEquals(1f, TraceScorer.coverage(letter, drawn))
        assertEquals(3, TraceScorer.score(letter, drawn))
    }

    @Test fun nothingDrawnIsZero() {
        assertEquals(0f, TraceScorer.coverage(letter, emptyList()))
        assertEquals(0, TraceScorer.stars(0f))
    }

    @Test fun halfTraceIsOneOrTwoStars() {
        // Only the top bar of the T (first stroke) is traced.
        val drawn = TraceScorer.sample(listOf(letter[0]), 100)
        val coverage = TraceScorer.coverage(letter, drawn)
        assertTrue(coverage in 0.3f..0.7f, "coverage $coverage")
        assertTrue(TraceScorer.stars(coverage) in 1..2)
    }

    @Test fun farAwayScribbleScoresNothing() {
        val drawn = List(50) { Point(1000f + it, 1000f) }
        assertEquals(0, TraceScorer.score(letter, drawn))
    }

    @Test fun starThresholds() {
        assertEquals(0, TraceScorer.stars(0.34f))
        assertEquals(1, TraceScorer.stars(0.35f))
        assertEquals(1, TraceScorer.stars(0.59f))
        assertEquals(2, TraceScorer.stars(0.6f))
        assertEquals(2, TraceScorer.stars(0.79f))
        assertEquals(3, TraceScorer.stars(0.8f))
    }

    @Test fun radiusIsThirtyPixels() {
        val line = LetterPaths.scaled('l', box, box)   // a single vertical stroke
        val samples = TraceScorer.sample(line, 49)
        val within = samples.map { Point(it.x + 29f, it.y) }
        val outside = samples.map { Point(it.x + 31f, it.y) }
        assertEquals(1f, TraceScorer.coverage(line, within))
        assertEquals(0f, TraceScorer.coverage(line, outside))
    }

    @Test fun everyLetterHasGeometry() {
        (('A'..'Z') + ('a'..'z')).forEach { c -> assertTrue(LetterPaths.strokes(c).flatten().size >= 2, "letter $c") }
    }
}
