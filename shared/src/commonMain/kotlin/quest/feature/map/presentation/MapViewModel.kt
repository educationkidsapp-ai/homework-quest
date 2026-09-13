package quest.feature.map.presentation

import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.map.domain.IslandStatus
import quest.feature.map.domain.IslandsUseCase
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State
import quest.feature.parent.domain.ParentRepository
import quest.feature.rewards.domain.RewardsRepository

class MapViewModel(
    private val islands: IslandsUseCase,
    private val rewards: RewardsRepository,
    private val parent: ParentRepository,
) : MviViewModel<State, Intent, Effect>(State()) {

    init { dispatch(Intent.Load) }

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> load()
            is Intent.TapIsland -> tap(intent.id)
            Intent.ReadAloud -> effect(Effect.Speak(readAloudText()))
        }
    }

    private suspend fun load() {
        val profile = parent.profile()
        val data = islands(Today.date())
        val streak = rewards.streak()
        val stickers = rewards.stickers().size
        reduce { copy(loading = false, childName = profile.name, islands = data.islands, isEmpty = data.isEmpty, streakDays = streak.currentDays, stickerCount = stickers) }
    }

    private suspend fun tap(id: String) {
        val island = current.islands.firstOrNull { it.id == id } ?: return
        when (island.status) {
            IslandStatus.ASLEEP -> effect(Effect.Speak("This island is still asleep."))
            else -> effect(Effect.OpenIntro(island.id))
        }
    }

    private fun readAloudText(): String = when {
        current.isEmpty -> "No quest today yet. Ask a grown-up to add today's lesson."
        current.islands.any { it.status == IslandStatus.TODAY } -> "Tap the glowing island to start today's quest!"
        else -> "You finished today's quest! Tap an island to play again."
    }
}
