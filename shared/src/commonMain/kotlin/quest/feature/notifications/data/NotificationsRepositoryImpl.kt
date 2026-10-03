package quest.feature.notifications.data

import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.NotificationView
import quest.feature.notifications.domain.NotificationsRepository
import quest.feature.notifications.domain.rowsFor

/** Straight through to `/me/notifications`, like the broadcast feed: a stale notification is worse than none. */
class NotificationsRepositoryImpl(private val api: ContentApi) : NotificationsRepository {
    override suspend fun rows(childId: String): List<NotificationView> = try {
        rowsFor(childId, api.notifications(limit = 50))
    } catch (e: ApiException) {
        // A server from before B3 has no parent rows: that is "none", not an error on the tab.
        if (e.error.code == ApiError.NOT_FOUND || e.error.code == ApiError.FORBIDDEN) emptyList() else throw e
    }

    override suspend fun markRead(id: String): NotificationView = api.markNotificationRead(id)
}
