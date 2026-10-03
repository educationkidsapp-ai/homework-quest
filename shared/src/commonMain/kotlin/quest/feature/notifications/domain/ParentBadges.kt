package quest.feature.notifications.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.dto.ChatFrame
import quest.api.dto.ChatSender
import quest.core.runCancellable
import quest.feature.children.domain.ChildrenRepository

/** M4 (D5): the two numbers the parent's bottom bar carries — what is unread on the Notifications tab and in Messages. */
data class UnreadCounts(val notifications: Int = 0, val messages: Int = 0)

/**
 * Where the counts come from. [notifications] is the parent's unread notification rows — until the server has parent
 * rows (B3), the unread announcements and events of her child's feed; [messages] is the unread messages over her
 * threads. Either answers null when it could not be asked (offline, flag off), and the badge then keeps its last value.
 */
interface UnreadSource {
    suspend fun notifications(childId: String): Int?
    suspend fun messages(childId: String): Int?
}

/**
 * M4 (D5): the badges, one instance for the app. [refresh] is called when a parent screen is shown and when the app
 * comes back to the front; [start] listens to `/ws/chat` so a message, a read, a resolve or a `notification` frame moves
 * the badges while the parent area is open, without waiting for the next screen.
 */
class ParentBadges(private val children: ChildrenRepository, private val source: UnreadSource) {
    private val state = MutableStateFlow(UnreadCounts())
    val counts: StateFlow<UnreadCounts> = state.asStateFlow()
    private val refreshing = Mutex()

    suspend fun refresh() = refreshing.withLock {
        val child = children.currentChild.value ?: run { state.value = UnreadCounts(); return@withLock }
        val notifications = runCancellable { source.notifications(child.id) }.getOrNull()
        val messages = runCancellable { source.messages(child.id) }.getOrNull()
        state.value = UnreadCounts(notifications ?: state.value.notifications, messages ?: state.value.messages)
    }

    /** Runs until [scope] is cancelled. */
    fun start(scope: CoroutineScope, frames: Flow<ChatFrame>): Job = scope.launch {
        frames.collect { frame -> if (movesBadges(frame)) refresh() }
    }

    companion object {
        /** A frame that changes a count: staff wrote, a thread was read, or the server wrote a notification row. */
        fun movesBadges(frame: ChatFrame): Boolean = when (frame) {
            is ChatFrame.Message -> frame.message.sender != ChatSender.PARENT
            is ChatFrame.Read, is ChatFrame.Notification -> true
            else -> false
        }
    }
}
