package quest.feature.map.presentation

import quest.api.dto.Child
import quest.api.dto.Island
import quest.feature.content.domain.ChildResult
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState

object MapContract {
    data class State(
        val loading: Boolean = true, val child: Child? = null, val islands: List<Island> = emptyList(),
        val streakDays: Int = 0, val offline: Boolean = false, val error: String? = null,
        /** §8: the lessons on the map that are exams — they get the exam card, never a score. */
        val exams: Set<String> = emptySet(),
        /** The device's clock when the map was loaded: what "open until" and the time left were worked out against. */
        val now: Long = 0,
        /** M4 (D4): the exams whose result the teacher has released, by lesson id — level and comment, never a score. */
        val marked: Map<String, ChildResult> = emptyMap(),
        /** M4 (D9): exams with at least one answer given — their card says "Continue exam". */
        val startedExams: Set<String> = emptySet(),
        /** M4 (D9): a pull-to-refresh is running. */
        val refreshing: Boolean = false,
    ) : MviState {
        val isEmpty: Boolean get() = islands.none { it.kind != quest.api.dto.IslandKind.LOCKED }
    }
    sealed interface Intent : MviIntent { data object Load : Intent; data object Refresh : Intent; data class TapIsland(val id: String) : Intent; data object ReadAloud : Intent }
    sealed interface Effect : MviEffect {
        data class Speak(val text: String) : Effect
        data class OpenLesson(val lessonId: String, val level: Int, val variant: Int) : Effect
        /** M4 (D4): the released result of an exam. */
        data class OpenResult(val lessonId: String) : Effect
        data object NeedsChild : Effect
    }
}
