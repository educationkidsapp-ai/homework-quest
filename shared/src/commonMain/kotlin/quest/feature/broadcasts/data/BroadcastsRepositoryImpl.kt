package quest.feature.broadcasts.data

import quest.api.ContentApi
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.feature.broadcasts.domain.BroadcastsRepository

/** Straight through to the contract: the feed is the server's answer, and there is nothing here to cache. */
class BroadcastsRepositoryImpl(private val contentApi: ContentApi) : BroadcastsRepository {
    override suspend fun feed(childId: String): BroadcastFeed = contentApi.childBroadcasts(childId)

    override suspend fun markRead(childId: String, broadcastId: String): BroadcastView =
        contentApi.markBroadcastRead(childId, broadcastId)
}
