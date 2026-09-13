package quest.feature.practice

import quest.api.dto.QuestionSet
import quest.api.samples.Samples
import quest.api.validation.SchemaValidator
import quest.feature.practice.domain.PracticeSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PracticeSessionTest {
    private val set = SchemaValidator.json.decodeFromString(QuestionSet.serializer(), Samples.questionSetCountingBy2s)

    @Test fun wrongAnswerDimsTileAndKeepsQuestion() {
        val s = PracticeSession(set)
        val outcome = s.answer("a")
        assertIs<PracticeSession.Outcome.Wrong>(outcome)
        assertEquals(setOf("a"), outcome.session.dimmed)
        assertEquals(0, outcome.session.index)
        assertEquals(0, outcome.session.stars)
        assertEquals("Start at 6 and jump 2.", outcome.hint)
        assertEquals(1, outcome.attemptNumber)
    }

    @Test fun secondTryCorrectIsNotFirstTry() {
        val wrong = PracticeSession(set).answer("a") as PracticeSession.Outcome.Wrong
        val right = wrong.session.answer("b") as PracticeSession.Outcome.Correct
        assertFalse(right.firstTry)
        assertEquals(2, right.attemptNumber)
        assertEquals(1, right.session.stars)
        assertEquals(0, right.session.firstTryCorrect)
    }

    @Test fun sevenCorrectAnswersCompleteTheSet() {
        var s = PracticeSession(set)
        repeat(7) {
            val q = s.question!!
            val out = s.answer(q.correctOptionId!!) as PracticeSession.Outcome.Correct
            assertTrue(out.firstTry)
            s = out.session.next()
        }
        assertTrue(s.isComplete)
        assertEquals(7, s.stars)
        assertEquals(7, s.firstTryCorrect)
        assertEquals(7, s.results.size)
    }

    @Test fun nextClearsDimmedTiles() {
        val wrong = PracticeSession(set).answer("a") as PracticeSession.Outcome.Wrong
        val right = wrong.session.answer("b") as PracticeSession.Outcome.Correct
        assertEquals(emptySet(), right.session.next().dimmed)
    }

    @Test fun traceBelowOneStarIsWrong() {
        val english = SchemaValidator.json.decodeFromString(QuestionSet.serializer(), Samples.questionSetShSound)
        val s = PracticeSession(english, index = 4)
        assertIs<PracticeSession.Outcome.Wrong>(s.trace(0))
        val ok = s.trace(2)
        assertIs<PracticeSession.Outcome.Correct>(ok)
        assertEquals(1, ok.session.stars)
    }
}
