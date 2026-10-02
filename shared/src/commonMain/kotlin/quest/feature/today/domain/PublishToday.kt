package quest.feature.today.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.Child
import quest.api.dto.Island
import quest.core.runCancellable
import quest.feature.chat.domain.ChatRepository

/**
 * Hands the widget what the home page has just shown. Called whenever the home page loads, so the widget is as fresh
 * as the app's last look at the server and never asks for anything itself. The unread count is best effort: a school
 * without messaging, or a device offline, simply shows none.
 */
class PublishTodayUseCase(private val store: TodaySnapshotStore, private val chat: ChatRepository, private val windows: ExamWindows) {
    suspend operator fun invoke(child: Child, islands: List<Island>, exams: Set<String>, now: Long, labels: TodayLabels, rtl: Boolean) {
        windows.remember(islands.filter { it.lessonId in exams && it.examWindow != null }.associate { it.lessonId!! to it.examWindow!!.closesAt })
        val unread = runCancellable { chat.threads(child.id).sumOf { it.unread } }.getOrDefault(0)
        runCancellable { store.write(TodaySnapshots.of(child.name, islands, exams, unread, now, labels, rtl)) }
    }
}

/** Widget taps waiting to be followed. The app's root collects them, so a tap lands behind the lock, not around it. */
object TodayLinks {
    private val _pending = MutableStateFlow<TodayLink?>(null)
    val pending: StateFlow<TodayLink?> = _pending
    fun open(key: String?) { TodayLink.of(key)?.let { _pending.value = it } }
    fun consumed() { _pending.value = null }
}
