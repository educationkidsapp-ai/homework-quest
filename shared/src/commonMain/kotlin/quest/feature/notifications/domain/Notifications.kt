package quest.feature.notifications.domain

import quest.api.dto.NotificationView

/**
 * M4 (D5), on B3's contract: the parent's own notification rows — a staff member wrote (`chat.message`), an exam's
 * result was released (`exam.released`), a homework was published (`homework.published`) — read over the same
 * `/me/notifications` routes as the dashboard bell. Broadcasts stay on the child's feed; the Notifications tab shows
 * both.
 */
interface NotificationsRepository {
    /** Her rows about [childId], newest first; empty when the server has none for parents (an older server). */
    suspend fun rows(childId: String): List<NotificationView>
    suspend fun markRead(id: String): NotificationView
}

/** A parent may have several children: the tab lists the current one's rows (and any row that names none). */
fun rowsFor(childId: String, rows: List<NotificationView>): List<NotificationView> =
    rows.filter { it.childId == null || it.childId == childId }
