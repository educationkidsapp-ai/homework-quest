package quest.feature.notifications.data

import quest.core.platform.Today
import quest.core.runCancellable
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.domain.unreadAnnouncements
import quest.feature.chat.domain.ChatRepository
import quest.feature.notifications.domain.UnreadSource
import quest.feature.notifications.domain.NotificationsRepository

/**
 * M4 (D5): the Notifications badge counts what the tab lists unread — her own notification rows about this child (B3)
 * and the feed's announcements and events — and the Messages badge the unread messages over her threads. The tab is
 * always there (M5); without `announcements` it counts her own rows only, because there is no feed to read.
 */
class ParentUnreadSource(
    private val broadcasts: BroadcastsRepository,
    private val notifications: NotificationsRepository,
    private val chat: ChatRepository,
    private val announcementsOn: () -> Boolean,
    private val chatOn: () -> Boolean,
) : UnreadSource {
    override suspend fun notifications(childId: String): Int? {
        val rows = runCancellable { notifications.rows(childId).count { it.readAt == null } }.getOrNull()
        val feed = if (!announcementsOn()) 0 else runCancellable { unreadAnnouncements(broadcasts.feed(childId).items, Today.epochMillis()) }.getOrNull()
        return if (rows == null && feed == null) null else (rows ?: 0) + (feed ?: 0)
    }

    override suspend fun messages(childId: String): Int? =
        if (!chatOn()) 0 else runCancellable { chat.threads(childId).sumOf { it.unread } }.getOrNull()
}
