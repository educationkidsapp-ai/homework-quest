package quest.api

import quest.api.dto.MultiAnswerLogic
import quest.api.dto.StopScoring
import kotlin.test.Test
import kotlin.test.assertEquals

class ScoringTest {
    @Test fun singleAnswerStars() { assertEquals(3, StopScoring.singleAnswer(1)); assertEquals(2, StopScoring.singleAnswer(2)); assertEquals(2, StopScoring.singleAnswer(5)) }

    @Test fun mistakesToStars() { assertEquals(3, StopScoring.byMistakes(0)); assertEquals(2, StopScoring.byMistakes(1)); assertEquals(2, StopScoring.byMistakes(2)); assertEquals(1, StopScoring.byMistakes(3)) }

    @Test fun attemptsToStars() { assertEquals(3, StopScoring.byAttempts(1)); assertEquals(2, StopScoring.byAttempts(2)); assertEquals(1, StopScoring.byAttempts(4)) }

    @Test fun multiSelectCheckSplitsRightAndWrong() {
        val c = MultiAnswerLogic.check(listOf("a", "x", "b"), listOf("a", "b", "c"))
        assertEquals(listOf("a", "b"), c.right)
        assertEquals(listOf("x"), c.wrong)
        // two rounds: 1 mistake then all right → 2 stars; correct picks are never reset
        val lit = c.right.toMutableSet(); var mistakes = c.wrong.size
        val c2 = MultiAnswerLogic.check(listOf("c"), listOf("a", "b", "c")); lit += c2.right; mistakes += c2.wrong.size
        assertEquals(setOf("a", "b", "c"), lit); assertEquals(2, StopScoring.byMistakes(mistakes))
    }

    @Test fun orderLockedPrefix() {
        assertEquals(2, MultiAnswerLogic.lockedPrefix(listOf("1", "2", "4", "3"), listOf("1", "2", "3", "4")))
        assertEquals(0, MultiAnswerLogic.lockedPrefix(listOf("2", "1"), listOf("1", "2")))
        assertEquals(3, MultiAnswerLogic.lockedPrefix(listOf("1", "2", "3"), listOf("1", "2", "3")))
    }

    @Test fun exitTicketAverage() { assertEquals(2, MultiAnswerLogic.exitTicketStars(listOf(3, 2, 2))); assertEquals(1, MultiAnswerLogic.exitTicketStars(listOf(1, 1, 2))); assertEquals(1, MultiAnswerLogic.exitTicketStars(emptyList())) }
}
