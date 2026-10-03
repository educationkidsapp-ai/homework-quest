package quest.ui.stops

import androidx.compose.runtime.staticCompositionLocalOf
import quest.api.dto.NumberLine

/** What a stop composable reports to the player (app) or the preview (admin). */
sealed interface StopEvent {
    /** Single-answer stop answered correctly on the given attempt (1 = first try). */
    data class Correct(val attempt: Int, val answer: String) : StopEvent
    /**
     * Single-answer stop answered wrongly: the player shows the hint sheet; the tile is already dimmed. [answer] is
     * the option chosen — in an exam it is the student's one and final answer.
     */
    data class Wrong(val attempt: Int, val hint: String, val numberLine: NumberLine?, val answer: String = "") : StopEvent
    /**
     * Info / multi / open / exit stop finished with the stars it earned. [correct] is false only in an exam, where a
     * stop is finished by answering it once, right or wrong.
     */
    data class Completed(val stars: Int, val answer: String = "", val mistakes: Int = 0, val recording: ByteArray? = null, val drawing: String? = null, val correct: Boolean = true) : StopEvent
    data class Speak(val text: String) : StopEvent
    /**
     * Exam only: one question inside an exit ticket was answered. The server grades an exit ticket by the questions in
     * it, so each goes up as its own attempt named by [questionId], with [answer] in the stop's exam format; the
     * ticket itself still finishes with [Completed].
     */
    data class QuestionAnswered(val questionId: String, val answer: String, val correct: Boolean, val stars: Int) : StopEvent
}

/**
 * §8: the stops are being sat as an exam. Every stop then takes **one** answer and gives nothing away: no tile dims or
 * lights up, nothing is said about right or wrong, no hint is offered, and the stop reports what was answered — the
 * player acknowledges every answer in the same words. Off by default; the exam player provides it.
 */
val LocalExamMode = staticCompositionLocalOf { false }

/**
 * Exam only: the exit-ticket questions this sitting has already answered (by id). A ticket left half-way — the student
 * went back home, or the app was killed — opens at its first unanswered question, never at one that was answered.
 */
val LocalAnsweredQuestions = staticCompositionLocalOf { emptySet<String>() }
