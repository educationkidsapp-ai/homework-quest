package quest.feature.chat.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.ContentApi
import quest.api.UploadFile
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatCommand
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatTopic
import quest.api.dto.SendChatMessageRequest
import quest.feature.chat.domain.AttachmentUploader
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.StagedUpload

class ChatRepositoryImpl(
    private val contentApi: ContentApi,
    private val socketClient: ChatSocketClient,
    private val uploader: AttachmentUploader = AttachmentUploader { childId, file, _ ->
        contentApi.uploadChatAttachment(childId, UploadFile(file.name, file.contentType, file.readBytes()))
    },
) : ChatRepository {

    override val connectionState: StateFlow<ChatConnectionState> = socketClient.connectionState
    override val incomingFrames: SharedFlow<ChatFrame> = socketClient.incomingFrames

    override suspend fun threads(childId: String): List<ChatThread> {
        return contentApi.chatThreads(childId)
    }

    override suspend fun coordinators(childId: String): List<ChatThread> {
        return contentApi.parentCoordinators(childId)
    }

    override suspend fun managers(childId: String): List<ChatThread> {
        return contentApi.childManagers(childId)
    }

    override suspend fun messages(
        childId: String,
        teacherId: String,
        before: String?,
        since: String?,
        limit: Int?,
    ): List<ChatMessage> {
        return contentApi.chatMessages(childId, teacherId, before = before, since = since, limit = limit)
    }

    override suspend fun sendMessage(
        childId: String,
        teacherId: String,
        body: String,
        clientId: String,
        topic: ChatTopic?,
        attachmentIds: List<String>,
    ): ChatMessage {
        val request = SendChatMessageRequest(body = body.trim(), clientId = clientId, topic = topic, attachmentIds = attachmentIds)
        // Sending via REST gives immediate guaranteed HTTP status (rate-limits, error handling)
        // while the server fans out the ChatFrame.Message(echo) to all active sessions including the socket.
        return contentApi.sendChatMessage(childId, teacherId, request)
    }

    override suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef =
        uploader.upload(childId, file, onProgress)

    override suspend fun markRead(childId: String, teacherId: String) {
        // Send socket command for immediate live notification to teacher
        socketClient.sendCommand(ChatCommand.Read(childId = childId, teacherId = teacherId))
        try {
            contentApi.markChatRead(childId, teacherId)
        } catch (_: Throwable) {
            // Ignore if thread does not exist yet (404) or network glitch
        }
    }

    override suspend fun sendTyping(childId: String, teacherId: String) {
        socketClient.sendCommand(ChatCommand.Typing(childId = childId, teacherId = teacherId))
    }

    override fun connect() {
        socketClient.connect()
    }

    override fun disconnect() {
        socketClient.disconnect()
    }
}
