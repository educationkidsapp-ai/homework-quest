package quest.feature.map.presentation

import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.feature.map.domain.Island

object MapContract {
    data class State(
        val loading: Boolean = true,
        val childName: String = "",
        val islands: List<Island> = emptyList(),
        val isEmpty: Boolean = false,
        val streakDays: Int = 0,
        val stickerCount: Int = 0,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class TapIsland(val id: String) : Intent
        data object ReadAloud : Intent
    }

    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenIntro(val skillId: String) : Effect
    }
}
