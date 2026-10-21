package quest.feature.channel.data

import quest.api.ContentApi
import quest.api.dto.ChannelPage
import quest.feature.channel.domain.ClassChannelRepository

/** Feature 32: the parent's two channel routes, straight through the contract's [ContentApi]. */
class ClassChannelRepositoryImpl(private val api: ContentApi) : ClassChannelRepository {
    override suspend fun page(childId: String, before: String?, limit: Int): ChannelPage =
        api.childChannel(childId, before = before, limit = limit)

    override suspend fun markRead(childId: String): ChannelPage = api.markChannelRead(childId)
}
