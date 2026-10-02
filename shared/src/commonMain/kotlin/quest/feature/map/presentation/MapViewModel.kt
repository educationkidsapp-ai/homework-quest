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
import quest.feature.content.domain.LessonRepository
import quest.feature.journey.presentation.isExam
import quest.feature.content.domain.MapRepository
import quest.feature.journey.presentation.LessonCopy
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State
import quest.feature.rewards.domain.RewardsRepository

class MapViewModel(
    private val children: ChildrenRepository,
    private val maps: MapRepository,
    private val journey: JourneyRepository,
    private val rewards: RewardsRepository,
    private val copy: LessonCopy,
    private val lessons: LessonRepository,
    private val now: () -> Long = Today::epochMillis,
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
        // An island with a window is an exam the server says is open; one without may still be an exam the device has
        // cached (the map came from the cache, or the paper was already handed in) and must not be drawn as homework.
        val exams = map.islands.filter { it.lessonId != null && (it.examWindow != null || runCatching { lessons.cached(it.lessonId!!) }.getOrNull()?.isExam == true) }.mapNotNull { it.lessonId }.toSet()
        reduce { copy(loading = false, child = child, islands = map.islands, streakDays = streak.currentDays, exams = exams, now = now()) }
    }

    private suspend fun tap(id: String) {
        val island = current.islands.firstOrNull { it.id == id } ?: return
        if (island.lessonId in current.exams) {
            // §8: an exam the server put on the map with a window is open — the device clock has no say. Handed in, or
            // known only from the cache, it stays shut and says why.
            val t = copy.strings()
            when (examStatus(island, loadedAt = current.now)) {
                ExamStatus.OPEN, ExamStatus.REOPENED -> effect(Effect.OpenLesson(island.lessonId ?: return, 1, 0))
                ExamStatus.SUBMITTED -> effect(Effect.Speak(t.examAlreadyTaken))
                ExamStatus.UNAVAILABLE -> effect(Effect.Speak(t.examNeedsConnection))
            }
            return
        }
        when (island.kind) {
            IslandKind.LOCKED -> effect(Effect.Speak(copy.strings().speakLocked))
            IslandKind.REVIEW -> effect(Effect.OpenLesson(island.lessonId ?: return, 1, 1))
            IslandKind.LESSON -> effect(Effect.OpenLesson(island.lessonId ?: return, 1, 0))
        }
    }

    private fun readAloudText(): String = when {
        current.isEmpty -> copy.strings().speakHomeEmpty
        current.islands.any { it.state == IslandState.TODAY } -> copy.strings().speakHomeToday
        else -> copy.strings().speakHome
    }
}
