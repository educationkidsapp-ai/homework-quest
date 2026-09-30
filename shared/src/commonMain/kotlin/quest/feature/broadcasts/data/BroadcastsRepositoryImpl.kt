package quest.feature.broadcasts.data

import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.WeeklyPlanArchive
import quest.core.db.SettingsStore
import quest.core.json.AppJson
import quest.feature.broadcasts.domain.BroadcastsRepository

/**
 * The announcements feed is straight through to the contract — RM4's reason still holds, that it is evaluated
 * server-side on every read and a stale announcement is worse than an empty screen.
 *
 * **The weekly-plan archive is cached, and only it.** MH3 downloads the plan's image to the device, and an image with
 * nothing to list it is an image the parent cannot reach: without the archive the page offline is an error card and
 * the cached bytes are unreachable. So the last archive is kept per child and answered when the request could not be
 * *made* — a network failure, nothing else. A 404 (the flag went off), a 403 or any other answer the server actually
 * gave is the truth and replaces what the device remembers.
 */
class BroadcastsRepositoryImpl(
    private val contentApi: ContentApi,
    private val settings: SettingsStore,
) : BroadcastsRepository {

    override suspend fun feed(childId: String): BroadcastFeed = contentApi.childBroadcasts(childId)

    override suspend fun plans(childId: String): WeeklyPlanArchive {
        val key = "$KEY_PLANS:$childId"
        return try {
            contentApi.childWeeklyPlans(childId).also {
                runCatching { settings.set(key, AppJson.encodeToString(WeeklyPlanArchive.serializer(), it)) }
            }
        } catch (e: Throwable) {
            if (!e.isOffline()) throw e
            val cached = settings.get(key) ?: throw e
            runCatching { AppJson.decodeFromString(WeeklyPlanArchive.serializer(), cached) }.getOrElse { throw e }
        }
    }

    override suspend fun markRead(childId: String, broadcastId: String): BroadcastView =
        contentApi.markBroadcastRead(childId, broadcastId)

    /** The request never reached the server: either the client's own network error, or no [ApiException] at all. */
    private fun Throwable.isOffline() = this !is ApiException || error.code == ApiError.NETWORK

    private companion object {
        const val KEY_PLANS = "weeklyPlans"
    }
}
