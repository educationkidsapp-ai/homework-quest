package quest.feature.journey

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import quest.api.dto.Stop
import quest.api.samples.PhonicsSeed
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.ui.design.TestTags
import quest.ui.stops.StopContent
import quest.ui.stops.StopEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Post-merge review of feature 30: what a stop has to report for the teacher's approved hints to be findable at all.
 * The hints are keyed by what she approved them for — a question inside an exit ticket, and a multi-answer stop —
 * so the stop that goes wrong has to name itself; before this it did not, and both kinds of approval did nothing.
 */
@OptIn(ExperimentalTestApi::class)
class SmartHintsUiTest {

    private val ticket = PhonicsSeed.level1.stops.filterIsInstance<Stop.ExitTicket>().first()
    private val match = PhonicsSeed.lesson.plays.flatMap { it.stops }.filterIsInstance<Stop.Match>().first()

    /** `HintRules.answerableStops` offers the teacher each question inside a ticket, under the question's own id. */
    @Test fun anExitTicketQuestionsWrongTryNamesTheQuestionAndNotTheTicket() = runComposeUiTest {
        val events = mutableListOf<StopEvent>()
        setContent { LessonTheme(LessonStrings.en, rtl = false) { StopContent(ticket, onEvent = { events += it }) } }
        val question = ticket.questions.first() as Stop.Sound
        onNodeWithText(question.options.first { it.id != question.correctOptionId }.label).performClick()
        waitForIdle()

        val wrong = events.filterIsInstance<StopEvent.Wrong>().single()
        assertEquals(question.id, wrong.stopId, "the id the hints are keyed by, not \"${ticket.id}\"")
        assertEquals(1, wrong.attempt)
    }

    /**
     * A multi-answer stop corrects itself in place and never reported a wrong try at all, so its approved hints could
     * not be drawn. It now says what went wrong — and the words it would have spoken, for when nothing was approved.
     */
    @Test fun aMatchStopsWrongPairingReportsAMistakeForTheStop() = runComposeUiTest {
        val events = mutableListOf<StopEvent>()
        setContent { LessonTheme(LessonStrings.en, rtl = false) { StopContent(match, onEvent = { events += it }) } }
        val (first, second) = match.pairs
        onNodeWithTag(TestTags.matchLeft(first.id)).performClick()
        onNodeWithTag(TestTags.matchRight(second.id)).performClick()
        waitForIdle()

        val mistake = events.filterIsInstance<StopEvent.Mistake>().single()
        assertEquals(match.id, mistake.stopId)
        assertEquals(1, mistake.attempt, "the first mistake earns the nudge, the second the pointer")
        assertTrue(mistake.say.isNotBlank(), "the stop's own words, said when the teacher approved no hints")
    }
}
