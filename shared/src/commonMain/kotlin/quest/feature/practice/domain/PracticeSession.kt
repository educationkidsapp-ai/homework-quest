package quest.feature.practice.domain

import quest.api.dto.NumberLine
import quest.api.dto.Question
import quest.api.dto.QuestionSet

/** Pure state machine for playing one question set. No red X: a wrong answer only dims a tile. */
data class PracticeSession(
    val set: QuestionSet,
    val index: Int = 0,
    val dimmed: Set<String> = emptySet(),
    val triesOnCurrent: Int = 0,
    val completed: Int = 0,
    val firstTryCorrect: Int = 0,
    val results: List<Result> = emptyList(),
) {
    data class Result(val questionId: String, val chosenOptionId: String?, val correct: Boolean, val attemptNumber: Int)

    sealed interface Outcome {
        data class Correct(val session: PracticeSession, val attemptNumber: Int, val firstTry: Boolean) : Outcome
        data class Wrong(val session: PracticeSession, val hint: String, val numberLine: NumberLine?, val attemptNumber: Int) : Outcome
    }

    val total: Int get() = set.questions.size
    val question: Question? get() = set.questions.getOrNull(index)
    val isComplete: Boolean get() = index >= total
    val stars: Int get() = completed

    fun answer(optionId: String): Outcome {
        val q = question ?: error("session complete")
        val attempt = triesOnCurrent + 1
        val correct = q.correctOptionId == optionId
        val result = Result(q.id, optionId, correct, attempt)
        return if (correct) {
            Outcome.Correct(
                copy(completed = completed + 1, firstTryCorrect = firstTryCorrect + if (attempt == 1) 1 else 0, results = results + result, triesOnCurrent = attempt),
                attempt, attempt == 1,
            )
        } else {
            Outcome.Wrong(copy(dimmed = dimmed + optionId, triesOnCurrent = attempt, results = results + result), q.hint, numberLineOf(q), attempt)
        }
    }

    /** Trace questions are scored by coverage; below one star counts as a wrong answer (hint, try again). */
    fun trace(stars: Int): Outcome {
        val q = question as? Question.Trace ?: error("not a trace question")
        val attempt = triesOnCurrent + 1
        val ok = stars >= 1
        val result = Result(q.id, null, ok, attempt)
        return if (ok) Outcome.Correct(copy(completed = completed + 1, firstTryCorrect = firstTryCorrect + if (attempt == 1) 1 else 0, results = results + result, triesOnCurrent = attempt), attempt, attempt == 1)
        else Outcome.Wrong(copy(triesOnCurrent = attempt, results = results + result), q.hint, null, attempt)
    }

    fun next(): PracticeSession = copy(index = index + 1, dimmed = emptySet(), triesOnCurrent = 0)

    companion object {
        fun numberLineOf(q: Question): NumberLine? = when (q) {
            is Question.Sequence -> q.numberLine
            is Question.Count -> q.numberLine
            is Question.Compare -> q.numberLine
            else -> null
        }
    }
}
