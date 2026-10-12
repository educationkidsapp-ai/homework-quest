package quest.feature.chat.data

import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatThread
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.ConversationSource
import quest.feature.chat.domain.StagedUpload

/**
 * The parent's end of a conversation: today's [ChatRepository], keyed by child and staff member, with no change of
 * behaviour at all. Every call below is the one the view model used to make inline.
 */
class ParentConversationSource(
    private val peer: ChatPeer,
    private val chat: ChatRepository,
) : ConversationSource {

    private val childId = peer.childId
    private val teacherId = peer.staffId

    override val connectionState = chat.connectionState
    override val incomingFrames = chat.incomingFrames

    override suspend fun messages(before: String?, since: String?, limit: Int?): List<ChatMessage> =
        chat.messages(childId, teacherId, before = before, since = since, limit = limit)

    override suspend fun send(body: String, clientId: String, attachmentIds: List<String>): ChatMessage =
        chat.sendMessage(childId, teacherId, body, clientId, attachmentIds)

    override suspend fun markRead() = chat.markRead(childId, teacherId)

    override suspend fun sendTyping() = chat.sendTyping(childId, teacherId)

    override suspend fun upload(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef =
        chat.uploadAttachment(childId, file, onProgress)

    /**
     * Her own rows: the thread list (teachers, and every coordinator, manager and administration thread that
     * exists), then the coordinator and manager choosers, whose rows carry the id too once a thread exists.
     */
    override suspend fun resolveThreadId(): String? {
        val lists: List<suspend () -> List<ChatThread>> = listOf({ chat.threads(childId) }, { chat.coordinators(childId) }, { chat.managers(childId) })
        for (list in lists) {
            val id = runCatching { list() }.getOrNull()?.firstOrNull { it.teacherId == teacherId && it.id != null }?.id
            if (id != null) return id
        }
        return null
    }

    override fun connect() = chat.connect()

    override fun isMine(message: ChatMessage): Boolean = message.sender == ChatSender.PARENT

    override fun isPeerMessage(message: ChatMessage): Boolean = message.sender == ChatSender.TEACHER && message.senderId == teacherId

    override fun isPeerRead(readBy: ChatSender): Boolean = readBy == ChatSender.TEACHER

    override fun isPeerTyping(from: ChatSender): Boolean = from == ChatSender.TEACHER

    override fun isPeer(userId: String): Boolean = userId == teacherId
}
