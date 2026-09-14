package quest.feature.journey.presentation

import quest.api.dto.Ingredient
import quest.api.dto.NumberLine
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.Stop
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.ui.journey.NodeState

object JourneyContract {
    data class State(
        val loading: Boolean = true, val lesson: PublishedLesson? = null, val play: Play? = null, val level: Int = 1, val variant: Int = 0,
        val levelsUnlocked: List<Int> = listOf(1), val completedLevels: List<Int> = emptyList(),
        val stopStars: Map<String, Int> = emptyMap(), val childName: String = "", val error: String? = null,
    ) : MviState {
        val stops: List<Stop> get() = play?.stops.orEmpty()
        val nodeStates: List<NodeState> get() { val firstOpen = stops.indexOfFirst { it.id !in stopStars }; return stops.mapIndexed { i, s -> when { s.id in stopStars -> NodeState.DONE; i == firstOpen -> NodeState.CURRENT; else -> NodeState.LOCKED } } }
        val collected: List<Ingredient> get() = stops.filter { it.id in stopStars }.map { it.ingredient }
        val nextIndex: Int get() = stops.indexOfFirst { it.id !in stopStars }.let { if (it < 0) 0 else it }
        val complete: Boolean get() = stops.isNotEmpty() && stops.all { it.id in stopStars }
    }
    sealed interface Intent : MviIntent { data object Load : Intent; data class SelectLevel(val level: Int) : Intent; data class TapStop(val index: Int) : Intent; data object ReadAloud : Intent; data object Serve : Intent }
    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenStop(val lessonId: String, val level: Int, val variant: Int, val index: Int) : Effect
        data class OpenComplete(val lessonId: String, val level: Int, val variant: Int) : Effect
    }
}

object PlayerContract {
    enum class Phase { LOADING, STOP, HINT, CORRECT, INGREDIENT, DONE, ERROR }
    data class State(
        val phase: Phase = Phase.LOADING, val lesson: PublishedLesson? = null, val play: Play? = null, val index: Int = 0,
        val stopStars: Map<String, Int> = emptyMap(), val hint: String = "", val numberLine: NumberLine? = null, val praise: String = "",
        val lastIngredient: Ingredient? = null, val childName: String = "", val error: String? = null,
    ) : MviState {
        val stop: Stop? get() = play?.stops?.getOrNull(index)
        val total: Int get() = play?.stops?.size ?: 0
        val collected: List<Ingredient> get() = play?.stops.orEmpty().filter { it.id in stopStars }.map { it.ingredient }
    }
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Correct(val attempt: Int, val answer: String) : Intent
        data class Wrong(val attempt: Int, val hint: String, val numberLine: NumberLine?, val answer: String) : Intent
        data class Completed(val stars: Int, val answer: String, val mistakes: Int) : Intent
        data object TryAgain : Intent
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
