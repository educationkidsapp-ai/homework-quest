package quest.feature.staff.domain

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
import quest.api.dto.ChatThread
import quest.core.runCancellable
import quest.feature.school.domain.FlagStore

/** What the staff bottom bar draws dots for: her unread conversations and her unread bell rows. */
data class StaffBadgeCounts(val messages: Int = 0, val notifications: Int = 0)

/**
 * The staff side's view of her own session, for the screens. The implementation lives in `staff/data` (it talks to
 * the server); the shell and the tabs only ever see this, which is what keeps `presentation` off the data layer.
 */
interface StaffSessionView {
    val profile: StateFlow<StaffProfile?>

    /** Her school has `staffMobileApp` off: there is no staff app to draw. */
    val disabled: StateFlow<Boolean>

    /** Her school's flags, which the staff subtree provides to `LocalFlags`. */
    val flagStore: FlagStore

    /**
     * Feature 19: `PUT /staff/me/preferences` — her after-hours alerts choice. Throws when the server refuses, and
     * [profile] is left exactly as it was, so the screen that asked can put its switch back.
     */
    suspend fun setAfterHoursAlerts(on: Boolean)
}

/**
 * The two dots on the staff bar, read once when a tab opens and again when the app comes back to the front — the
 * same rhythm `ParentBadges` keeps, and the same reason: a badge that is only ever pushed goes stale the moment one
 * push is missed.
 */
class StaffBadges(private val api: StaffGateway, private val profile: () -> StaffProfile?) {
    private val _counts = MutableStateFlow(StaffBadgeCounts())
    val counts: StateFlow<StaffBadgeCounts> = _counts.asStateFlow()

    /** One refresh at a time: a screen that opens asks for the counts while the shell around it is already asking. */
    private val refreshing = Mutex()

    suspend fun refresh() = refreshing.withLock {
        val me = profile() ?: run { _counts.value = StaffBadgeCounts(); return@withLock }
        val messages = if (!me.hasChat) 0 else runCancellable { api.staffThreads().sumOf { it.unread } }.getOrNull() ?: _counts.value.messages
        val notifications = runCancellable { api.unreadNotificationCount().count }.getOrNull() ?: _counts.value.notifications
        _counts.value = StaffBadgeCounts(messages = messages, notifications = notifications)
    }

    /**
     * The rows a screen has just fetched, counted here instead of asking for them again — what the Messages tab
     * knows is what the bar and the home card should say.
     */
    fun observed(threads: List<ChatThread>) {
        _counts.value = _counts.value.copy(messages = threads.sumOf { it.unread })
    }

    /**
     * Feature 13 (fix): the live half, as `ParentBadges.start` is. A message, a read or a notification frame moves
     * the counts wherever she is in the staff app — the home's "unread messages" card is drawn from this very flow,
     * so it no longer waits for the Messages tab to be opened again. Runs until [scope] is cancelled.
     */
    fun start(scope: CoroutineScope, frames: Flow<ChatFrame>): Job = scope.launch {
        frames.collect { frame -> if (movesBadges(frame)) refresh() }
    }

    private fun movesBadges(frame: ChatFrame): Boolean = when (frame) {
        // Her own send comes back as a frame too, and it changes nothing she has to read.
        is ChatFrame.Message -> frame.message.senderId != profile()?.userId
        is ChatFrame.Read, is ChatFrame.Notification -> true
        else -> false
    }

    /** She opened a thread (or the bell) and it is read now: the dot goes without waiting for the next refresh. */
    fun readMessages(threadUnread: Int) {
        _counts.value = _counts.value.copy(messages = (_counts.value.messages - threadUnread).coerceAtLeast(0))
    }

    fun readAllNotifications() {
        _counts.value = _counts.value.copy(notifications = 0)
    }
}
