package quest.feature.practice

import quest.feature.practice.domain.Band
import quest.feature.practice.domain.ProgressBands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressBandsTest {
    private fun results(correct: Int, total: Int) = List(total) { it < correct }

    @Test fun noAttemptsHasNoBand() { assertNull(ProgressBands.band(emptyList())) }

    @Test fun goingWellAt85Percent() {
        assertEquals(Band.GOING_WELL, ProgressBands.band(results(12, 14)))   // 85.7 %
        assertEquals(Band.GOING_WELL, ProgressBands.band(results(17, 20)))   // 85 % after windowing: first 14 all correct
    }

    @Test fun gettingThereBetween60And84() {
        assertEquals(Band.GETTING_THERE, ProgressBands.band(results(11, 14))) // 78.6 %
        assertEquals(Band.GETTING_THERE, ProgressBands.band(results(9, 14)))  // 64.3 %
        assertEquals(Band.GETTING_THERE, ProgressBands.band(6 to 10))
    }

    @Test fun needsAnotherLookBelow60() {
        assertEquals(Band.NEEDS_ANOTHER_LOOK, ProgressBands.band(results(8, 14))) // 57 %
        assertEquals(Band.NEEDS_ANOTHER_LOOK, ProgressBands.band(listOf(false)))
    }

    @Test fun onlyTheLastFourteenCount() {
        // 14 most recent are all wrong, older ones all right → needs another look
        val recentWrong = List(14) { false } + List(20) { true }
        assertEquals(Band.NEEDS_ANOTHER_LOOK, ProgressBands.band(recentWrong))
    }

    @Test fun boundaries() {
        assertEquals(Band.GOING_WELL, ProgressBands.band(0.85))
        assertEquals(Band.GETTING_THERE, ProgressBands.band(0.849))
        assertEquals(Band.GETTING_THERE, ProgressBands.band(0.60))
        assertEquals(Band.NEEDS_ANOTHER_LOOK, ProgressBands.band(0.599))
    }

    private fun ProgressBands.band(pair: Pair<Int, Int>) = band(results(pair.first, pair.second))
}
