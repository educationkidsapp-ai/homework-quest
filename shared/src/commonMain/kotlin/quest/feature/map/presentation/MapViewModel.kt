package quest.feature.map.presentation

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.MapRepository
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State
import quest.feature.rewards.domain.RewardsRepository

class MapViewModel(
    private val children: ChildrenRepository,
    private val maps: MapRepository,
    private val journey: JourneyRepository,
    private val rewards: RewardsRepository,
) : MviViewModel<State, Intent, Effect>(State()) {

    override suspend fun handle(intent: Intent) {
        when (intent) {
            Intent.Load -> load()
            is Intent.TapIsland -> tap(intent.id)
            Intent.ReadAloud -> effect(Effect.Speak(readAloudText()))
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value ?: children.refresh().let { children.currentChild.value }
        if (child == null) { effect(Effect.NeedsChild); return }
        runCatching { journey.flushAttempts(child.id) }
        val today = Today.date()
        val map = maps.map(child, today.minus(30, DateTimeUnit.DAY), today.plus(7, DateTimeUnit.DAY), today)
        val streak = rewards.streak()
        reduce { copy(loading = false, child = child, islands = map.islands, streakDays = streak.currentDays) }
    }

    private suspend fun tap(id: String) {
        val island = current.islands.firstOrNull { it.id == id } ?: return
        when (island.kind) {
            IslandKind.LOCKED -> effect(Effect.Speak("This island is still asleep."))
            IslandKind.REVIEW -> effect(Effect.OpenLesson(island.lessonId ?: return, 1, 1))
            IslandKind.LESSON -> effect(Effect.OpenLesson(island.lessonId ?: return, 1, 0))
        }
    }

    private fun readAloudText(): String = when {
        current.isEmpty -> "No quest today yet. Ask a grown-up to check the map tomorrow."
        current.islands.any { it.state == IslandState.TODAY } -> "Tap the glowing island to start today's quest!"
        else -> "Tap an island to play."
    }
}
