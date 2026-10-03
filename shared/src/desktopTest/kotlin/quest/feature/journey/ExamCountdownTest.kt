package quest.feature.journey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import quest.core.text.localDigits
import quest.feature.journey.presentation.ExamCountdown
import quest.feature.journey.presentation.ExamCountdownRow
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M5: the exam sitting's countdown — its words, its warning, its three announcements — on a fake clock. */
@OptIn(ExperimentalTestApi::class)
class ExamCountdownTest {
    private val min = 60_000L

    @Test fun theClockIsMinutesAndSecondsAndHoursOnlyAboveAnHour() {
        assertEquals("12:34", ExamCountdown.clock(12 * min + 34_000))
        assertEquals("60:00", ExamCountdown.clock(60 * min), "exactly an hour is still minutes")
        assertEquals("1:00:01", ExamCountdown.clock(60 * min + 1))
        assertEquals("2:05:09", ExamCountdown.clock(125 * min + 9_000))
    }

    @Test fun secondsRoundUpSoZeroMeansTheWindowHasShut() {
        assertEquals("00:01", ExamCountdown.clock(1))
        assertEquals("00:01", ExamCountdown.clock(999))
        assertEquals("00:00", ExamCountdown.clock(0))
        assertEquals("00:00", ExamCountdown.clock(-5_000), "never a negative time")
    }

    @Test fun theLastFiveMinutesWarn() {
        assertFalse(ExamCountdown.warning(5 * min + 1))
        assertTrue(ExamCountdown.warning(5 * min))
        assertTrue(ExamCountdown.warning(0))
    }

    @Test fun eachMilestoneIsAnnouncedOnceWhenCrossed() {
        assertEquals(10, ExamCountdown.milestone(10 * min + 1, 10 * min))
        assertNull(ExamCountdown.milestone(10 * min, 10 * min - 1_000), "already said")
        assertEquals(5, ExamCountdown.milestone(5 * min + 400, 5 * min - 600))
        assertEquals(1, ExamCountdown.milestone(12 * min, 30_000), "a device that slept says only the latest")
        assertNull(ExamCountdown.milestone(9 * min, 8 * min))
        assertEquals("1 minute left in this exam.", ExamCountdown.announcement(1, LessonStrings.en))
        assertEquals("5 minutes left in this exam.", ExamCountdown.announcement(5, LessonStrings.en))
    }

    @Test fun theTickerWaitsForTheNextWholeSecond() {
        assertEquals(1000, ExamCountdown.untilNextSecond(5_000))
        assertEquals(400, ExamCountdown.untilNextSecond(5_400))
        assertEquals(1, ExamCountdown.untilNextSecond(5_001))
    }

    @Test fun arabicReadsArabicDigits() {
        assertEquals("١٢:٠٥", localDigits("12:05", arabic = true))
        assertEquals("12:05", localDigits("12:05", arabic = false))
    }

    @Test fun theRowCountsDownTurnsToWarningAndAnnouncesPolitely() = runComposeUiTest {
        mainClock.autoAdvance = false
        val closes = 1_790_000_000_000L
        var now = closes - 5 * min - 2_000                      // 05:02 left
        setContent { LessonTheme(LessonStrings.en, rtl = false) { ExamCountdownRow(closes, now = { now }) } }
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Time left 05:02").assertExists()
        onAllNodesWithTag("exam-countdown").assertCountEquals(1)
        onAllNodesWithTag("exam-countdown-warning").assertCountEquals(0)

        now += 3_000                                              // 04:59 — past the 5-minute mark
        mainClock.advanceTimeBy(3_000)
        onNodeWithContentDescription("Time left 04:59").assertExists()
        onAllNodesWithTag("exam-countdown-warning").assertCountEquals(1)
        val announced = SemanticsMatcher("polite live region") { it.config.getOrNull(SemanticsProperties.LiveRegion) != null }
        val region = onAllNodes(announced).fetchSemanticsNodes().single()
        assertEquals(listOf("5 minutes left in this exam."), region.config.getOrNull(SemanticsProperties.ContentDescription))

        now = closes                                              // the window shuts: 00:00, and the ticker stops
        mainClock.advanceTimeBy(5 * min)
        onNodeWithContentDescription("Time left 00:00").assertExists()
        assertEquals(listOf("1 minute left in this exam."), onAllNodes(announced).fetchSemanticsNodes().single().config.getOrNull(SemanticsProperties.ContentDescription))
    }
}
