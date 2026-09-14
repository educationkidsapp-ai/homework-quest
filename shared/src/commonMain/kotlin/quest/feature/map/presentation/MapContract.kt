package quest.feature.map.presentation

import quest.api.dto.Child
import quest.api.dto.Island
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState

object MapContract {
    data class State(
        val loading: Boolean = true, val child: Child? = null, val islands: List<Island> = emptyList(),
        val streakDays: Int = 0, val offline: Boolean = false, val error: String? = null,
    ) : MviState {
        val isEmpty: Boolean get() = islands.none { it.kind != quest.api.dto.IslandKind.LOCKED }
    }
    sealed interface Intent : MviIntent { data object Load : Intent; data class TapIsland(val id: String) : Intent; data object ReadAloud : Intent }
    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenLesson(val lessonId: String, val level: Int, val variant: Int) : Effect
        data object NeedsChild : Effect
    }
}
