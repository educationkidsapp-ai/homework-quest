package quest.feature.staff.data

import quest.api.dto.AttachmentRef
import quest.api.dto.ChatCommand
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.SendChatMessageRequest
import quest.feature.chat.data.ChatSocketClient
import quest.feature.chat.domain.ConversationSource
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffThreadKey

/**
 * Feature 13: a staff member's end of a conversation — `/staff/chat/threads/{id}/…`, the `/ws/chat` socket with her
 * own token, and `POST /media/chat-attachments`.
 *
 * **Orientation.** "Mine" is her own user id, not a `sender` word: a staff thread has a staff member at both ends and
 * both of them are `teacher` on the wire. A `read` frame is only taken as the other side's when it says `parent`, for
 * the same reason — the server sends `read` to everyone on the thread, her own sessions included, so trusting
 * `teacher` there would tick her own messages as read by herself.
 *
 * **Typing.** The socket keys a teacher's commands by child and everybody else's by thread, and refuses a teacher's
 * `threadId` unless it is one of her *staff* threads. So a teacher typing to a parent sends the child form, which is
 * what the handler expects of her, and everyone else sends the thread form.
 */
class StaffConversationSource(
    private val key: StaffThreadKey,
    private val api: StaffGateway,
    private val socket: ChatSocketClient,
) : ConversationSource {

    override val connectionState = socket.connectionState
    override val incomingFrames = socket.incomingFrames

    override suspend fun messages(before: String?, since: String?, limit: Int?): List<ChatMessage> =
        api.staffMessages(key.threadId, before = before, since = since, limit = limit)

    override suspend fun send(body: String, clientId: String, attachmentIds: List<String>): ChatMessage =
        api.staffSend(key.threadId, SendChatMessageRequest(body = body.trim(), clientId = clientId, attachmentIds = attachmentIds))

    override suspend fun markRead() {
        // REST only: the server publishes the `read` event from here too, and the socket's read command for a
        // teacher's parent thread would have to be keyed by child, which this screen has no reason to care about.
        runCatching { api.staffRead(key.threadId) }
    }

    override suspend fun sendTyping() {
        val command = if (key.typeByChild) ChatCommand.Typing(childId = key.childId) else ChatCommand.Typing(threadId = key.threadId)
        socket.sendCommand(command)
    }

    override suspend fun upload(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = api.uploadAttachment(file, onProgress)

    /** Her threads always arrive with an id — that is how the facade names them — so there is nothing to resolve. */
    override suspend fun resolveThreadId(): String? = key.threadId

    override fun connect() = socket.connect()

    override fun isMine(message: ChatMessage): Boolean = message.senderId == key.myUserId

    override fun isPeerMessage(message: ChatMessage): Boolean = message.senderId != key.myUserId

    override fun isPeerRead(readBy: ChatSender): Boolean = readBy == ChatSender.PARENT

    /** A `typing` frame never reaches the person who sent it (`ChatHub`), so one on this thread is hers. */
    override fun isPeerTyping(from: ChatSender): Boolean = true

    override fun isPeer(userId: String): Boolean = userId == key.peerUserId
}
