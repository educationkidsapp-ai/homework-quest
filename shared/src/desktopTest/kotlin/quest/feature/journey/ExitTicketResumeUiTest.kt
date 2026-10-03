package quest.feature.journey

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import quest.api.dto.Stop
import quest.api.samples.MathSeed
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.ui.stops.LocalAnsweredQuestions
import quest.ui.stops.LocalExamMode
import quest.ui.stops.StopContent
import quest.ui.stops.StopEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review of #197: an exam's exit ticket opens at its first unanswered question, and one fully answered finishes. */
@OptIn(ExperimentalTestApi::class)
class ExitTicketResumeUiTest {
    private val ticket = MathSeed.lesson.plays[0].stops.filterIsInstance<Stop.ExitTicket>().first()

    @Test fun aHalfDoneTicketOpensAtItsNextQuestion() = runComposeUiTest {
        val events = mutableListOf<StopEvent>()
        val (q1, q2, q3) = ticket.questions
        setContent {
            LessonTheme(LessonStrings.en, rtl = false) {
                CompositionLocalProvider(LocalExamMode provides true, LocalAnsweredQuestions provides setOf(q1.id, q2.id)) { StopContent(ticket, onEvent = { events += it }) }
            }
        }
        onNodeWithContentDescription("<").performClick()           // only the third question (a compare) has this tile
        waitForIdle()
        assertEquals(q3.id, events.filterIsInstance<StopEvent.QuestionAnswered>().single().questionId)
    }

    @Test fun aTicketAnsweredToTheEndFinishesWithoutAskingAgain() = runComposeUiTest {
        val events = mutableListOf<StopEvent>()
        setContent {
            LessonTheme(LessonStrings.en, rtl = false) {
                CompositionLocalProvider(LocalExamMode provides true, LocalAnsweredQuestions provides ticket.questions.map { it.id }.toSet()) { StopContent(ticket, onEvent = { events += it }) }
            }
        }
        waitForIdle()
        assertEquals(0, events.filterIsInstance<StopEvent.QuestionAnswered>().size)
        assertEquals(1, events.filterIsInstance<StopEvent.Completed>().size)
    }
}
