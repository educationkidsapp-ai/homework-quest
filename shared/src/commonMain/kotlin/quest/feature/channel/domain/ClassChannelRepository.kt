package quest.feature.channel.domain

import quest.api.dto.ChannelPage

/**
 * Feature 32: the class channel as a parent reads it — `GET /children/{id}/channel` and
 * `POST /children/{id}/channel/read`.
 *
 * **Read-only on purpose.** Parents do not post in the channel, so there is nothing to send here at all; an answer is
 * an ordinary one-to-one message to the post's author ("Reply privately"), which the chat already does.
 */
interface ClassChannelRepository {
    /** A page of posts, oldest first. [before] is a post id, as a chat thread's paging is. */
    suspend fun page(childId: String, before: String? = null, limit: Int = PAGE_SIZE): ChannelPage

    /** Every post is read; answers her page, so opening the channel is one request rather than two. */
    suspend fun markRead(childId: String): ChannelPage

    companion object {
        /** A page of posts. A channel is a term's worth of notices, not a conversation, so this is read in handfuls. */
        const val PAGE_SIZE = 30
    }
}
