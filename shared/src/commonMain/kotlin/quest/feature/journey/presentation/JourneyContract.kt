package quest.feature.journey.presentation

import quest.api.dto.NumberLine
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.Stop
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.feature.content.domain.SubmitOutcome
import quest.ui.journey.NodeState

object JourneyContract {
    data class State(
        val loading: Boolean = true, val lesson: PublishedLesson? = null, val play: Play? = null, val level: Int = 1, val variant: Int = 0,
        val levelsUnlocked: List<Int> = listOf(1), val completedLevels: List<Int> = emptyList(),
        val stopStars: Map<String, Int> = emptyMap(), val childName: String = "", val error: String? = null,
        /** §8: an exam is one play with no level chooser, no hints and no way back into an answered question. */
        val exam: Boolean = false,
        /**
         * Feature 30: the skill this play practises, when the play is a targeted practice set
         * ([Play.PRACTICE_VARIANT] and up) rather than a level or the Again variant. It names the one skill on the
         * screen and takes the level chooser away: a practice set is not a level and there is nothing to choose.
         */
        val practiceSkill: String? = null,
    ) : MviState {
        val stops: List<Stop> get() = play?.stops.orEmpty()
        val nodeStates: List<NodeState> get() { val firstOpen = stops.indexOfFirst { it.id !in stopStars }; return stops.mapIndexed { i, s -> when { s.id in stopStars -> NodeState.DONE; i == firstOpen -> NodeState.CURRENT; else -> NodeState.LOCKED } } }
        val doneCount: Int get() = stops.count { it.id in stopStars }
        val nextIndex: Int get() = stops.indexOfFirst { it.id !in stopStars }.let { if (it < 0) 0 else it }
        val complete: Boolean get() = stops.isNotEmpty() && stops.all { it.id in stopStars }
    }
    sealed interface Intent : MviIntent { data object Load : Intent; data class SelectLevel(val level: Int) : Intent; data class TapStop(val index: Int) : Intent; data object ReadAloud : Intent; data object Finish : Intent }
    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenStop(val lessonId: String, val level: Int, val variant: Int, val index: Int) : Effect
        data class OpenComplete(val lessonId: String, val level: Int, val variant: Int) : Effect
    }
}

object PlayerContract {
    /**
     * [REFUSED] is §8's `409` "already taken": the server holds a handed-in paper. [SENDING] is a finished paper whose
     * answers have not all reached the server yet — it is not "submitted" until they have.
     */
    enum class Phase { LOADING, STOP, HINT, CORRECT, STEP_DONE, DONE, ERROR, REFUSED, SENDING }
    data class State(
        val phase: Phase = Phase.LOADING, val lesson: PublishedLesson? = null, val play: Play? = null, val index: Int = 0,
        val stopStars: Map<String, Int> = emptyMap(), val hint: String = "", val numberLine: NumberLine? = null, val praise: String = "",
        val childName: String = "", val error: String? = null, val exam: Boolean = false,
        /** Why the server would not take the sitting; set with [Phase.REFUSED]. */
        val refusal: SubmitOutcome? = null,
        /**
         * M4 (D8): when this sitting's window closes, by the server's clock — shown as a time of day ("Closes at
         * 10:40"), never as a ticking timer (§7). Null for a homework, a re-opened sitting, or an end not known.
         */
        val closesAt: Long? = null,
        /** Exam: the exit-ticket questions answered so far, so a resumed ticket starts at the next one. */
        val answeredQuestions: Set<String> = emptySet(),
    ) : MviState {
        val stop: Stop? get() = play?.stops?.getOrNull(index)
        val total: Int get() = play?.stops?.size ?: 0
        val doneCount: Int get() = play?.stops.orEmpty().count { it.id in stopStars }
    }
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Correct(val attempt: Int, val answer: String) : Intent
        data class Wrong(val attempt: Int, val hint: String, val numberLine: NumberLine?, val answer: String) : Intent
        data class Completed(val stars: Int, val answer: String, val mistakes: Int, val recording: ByteArray? = null, val drawing: String? = null, val correct: Boolean = true) : Intent
        data object TryAgain : Intent
        /** [Phase.SENDING]: try to hand the queued answers in again. */
        data object SendAgain : Intent
        /** Exam: one question inside an exit ticket was answered — its own attempt, by the question's id. */
        data class QuestionAnswered(val questionId: String, val answer: String, val correct: Boolean, val stars: Int) : Intent
        /** M4 (D8): the window's closing time has passed by the server's clock — no more answers are taken. */
        data object WindowClosed : Intent
        data object Advance : Intent
        data object ReadAloud : Intent
        data class Speak(val text: String) : Intent
    }
    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class Finished(val lessonId: String, val level: Int, val variant: Int) : Effect
        data object BackToJourney : Effect
    }
}

/** §8: a lesson of type `exam` that carries the one play it is sat over. */
val PublishedLesson.isExam: Boolean get() = type == "exam" && examPlay != null

/**
 * The play a route's ([level], [variant]) means for this lesson. An exam ignores both — "the app plays exactly
 * [PublishedLesson.examPlay] and ignores `plays` and `variant`" — so the overview, the player and the result all agree
 * on the same questions and the answers land on the play the teacher's scorer reads.
 */
fun PublishedLesson.playFor(level: Int, variant: Int): Play = examPlay?.takeIf { isExam }
    ?: play(level, variant)
    // Feature 30: a practice set the review island named and this body does not carry — the teacher withdrew it, or
    // the map is newer than the lesson on this device. The island's own fallback is the Again play, so the child gets
    // the review she tapped and never the whole lesson again.
    ?: variant.takeIf { it >= Play.PRACTICE_VARIANT }?.let { play(1, 1) }
    ?: plays.first()

/**
 * Feature 30: the hint one wrong try has earned, or null when this lesson has no approved hints for the stop and the
 * stop's own single [Stop.hint] stands as before.
 *
 * The first wrong try gets the nudge, the second the stronger pointer, and a third and later try keeps the last one —
 * there is nothing after it, and the child is never left without help she already had. An exam has neither: it carries
 * no hints at all ([PublishedLesson.hintsOff]), which is checked here as well as by the player.
 */
fun PublishedLesson.smartHint(stopId: String, attempt: Int): String? {
    if (isExam || hintsOff) return null
    val hints = smartHints[stopId]?.filter { it.isNotBlank() }.orEmpty()
    if (hints.isEmpty()) return null
    return hints[(attempt - 1).coerceIn(hints.indices)]
}
