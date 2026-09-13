package quest.feature.practice.presentation

import quest.api.dto.GenerateMode
import quest.api.dto.NumberLine
import quest.api.dto.Question
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState

object PracticeContract {
    enum class Phase { LOADING, QUESTION, HINT, CORRECT, COMPLETE, GENERATING, ERROR }

    data class State(
        val phase: Phase = Phase.LOADING,
        val setId: String = "",
        val skillId: String = "",
        val skillName: String = "",
        val index: Int = 0,
        val total: Int = 7,
        val stars: Int = 0,
        val question: Question? = null,
        val dimmed: Set<String> = emptySet(),
        val hint: String = "",
        val numberLine: NumberLine? = null,
        val praise: String = "",
        val stickerKey: String? = null,
        val traceStars: Int = 0,
        val firstTryCorrect: Int = 0,
        val errorMessage: String? = null,
        val prompt: String = "",
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Answer(val optionId: String) : Intent
        data class TraceFinished(val coverage: Float) : Intent
        data object TryAgain : Intent
        data object Advance : Intent
        data object ReadAloud : Intent
        data class SpeakWord(val text: String) : Intent
        data class More(val mode: GenerateMode) : Intent
    }

    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenSet(val setId: String) : Effect
        data object GoToMap : Effect
        data object GoToStickers : Effect
    }
}
