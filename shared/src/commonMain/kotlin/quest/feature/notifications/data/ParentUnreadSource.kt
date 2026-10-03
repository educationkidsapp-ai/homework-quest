package quest.feature.notifications.data

import quest.core.platform.Today
import quest.core.runCancellable
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.domain.unreadAnnouncements
import quest.feature.chat.domain.ChatRepository
import quest.feature.notifications.domain.UnreadSource

/**
 * M4 (D5), until B3: the Notifications badge counts the unread announcements and events of the child's feed (what the
 * tab lists today), the Messages badge the unread messages over her threads. When the server answers the parent's
 * own notification rows, [notifications] becomes its unread count and nothing above this class changes.
 */
class ParentUnreadSource(
    private val broadcasts: BroadcastsRepository,
    private val chat: ChatRepository,
    private val announcementsOn: () -> Boolean,
    private val chatOn: () -> Boolean,
) : UnreadSource {
    override suspend fun notifications(childId: String): Int? =
        if (!announcementsOn()) 0 else runCancellable { unreadAnnouncements(broadcasts.feed(childId).items, Today.epochMillis()) }.getOrNull()

    override suspend fun messages(childId: String): Int? =
        if (!chatOn()) 0 else runCancellable { chat.threads(childId).sumOf { it.unread } }.getOrNull()
}
