package quest.feature.staff.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/**
 * The two dots on the staff bar, read once when a tab opens and again when the app comes back to the front — the
 * same rhythm `ParentBadges` keeps, and the same reason: a badge that is only ever pushed goes stale the moment one
 * push is missed.
 */
class StaffBadges(private val api: StaffGateway, private val profile: () -> StaffProfile?) {
    private val _counts = MutableStateFlow(StaffBadgeCounts())
    val counts: StateFlow<StaffBadgeCounts> = _counts.asStateFlow()

    suspend fun refresh() {
        val me = profile() ?: run { _counts.value = StaffBadgeCounts(); return }
        val messages = if (!me.hasChat) 0 else runCancellable { api.staffThreads().sumOf { it.unread } }.getOrNull() ?: _counts.value.messages
        val notifications = runCancellable { api.unreadNotificationCount().count }.getOrNull() ?: _counts.value.notifications
        _counts.value = StaffBadgeCounts(messages = messages, notifications = notifications)
    }

    /** She opened a thread (or the bell) and it is read now: the dot goes without waiting for the next refresh. */
    fun readMessages(threadUnread: Int) {
        _counts.value = _counts.value.copy(messages = (_counts.value.messages - threadUnread).coerceAtLeast(0))
    }

    fun readAllNotifications() {
        _counts.value = _counts.value.copy(notifications = 0)
    }
}
