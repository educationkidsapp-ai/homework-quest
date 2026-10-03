package quest.feature.chat.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic

/**
 * M7: how long "… is typing" stays up without another `typing` frame. The dashboard sends one at most every 2 s while
 * somebody types, so this outlasts two missed frames, and the next message from her clears it at once.
 */
const val TYPING_TIMEOUT_MS = 5_000L

enum class ChatConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR,
}

/**
 * C3: Parent-side chat domain repository.
 * Manages REST message history, WebSocket real-time frame streaming,
 * optimistic message dispatching, typing indications, and reconnect synchronization.
 */
interface ChatRepository {
    val connectionState: StateFlow<ChatConnectionState>
    val incomingFrames: SharedFlow<ChatFrame>

    suspend fun threads(childId: String): List<ChatThread>

    /**
     * R8: the coordinators the parent may open a thread with, as rows shaped like [threads] (`id` null until she
     * writes). A coordinator is not one of the child's teachers, so she is not in [threads] until a thread exists.
     */
    suspend fun coordinators(childId: String): List<ChatThread>

    /**
     * RM4 (DR5): the manager(s) of the department her child's section belongs to, as the same unwritten thread rows
     * [coordinators] answers. A `complaint` is allowed here too — a complaint *about* a coordinator has nowhere else
     * to go.
     */
    suspend fun managers(childId: String): List<ChatThread>
    suspend fun messages(childId: String, teacherId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>
    /**
     * [topic] is read by the server only when this message creates the thread. M7 (B5): [attachmentIds] are uploads
     * from [uploadAttachment], at most five, and [body] may then be empty.
     */
    suspend fun sendMessage(
        childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic? = null,
        attachmentIds: List<String> = emptyList(),
    ): ChatMessage

    /** M7 (B5): one staged photo or PDF for a message about [childId]; [onProgress] runs 0..1 while the bytes go up. */
    suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit = {}): AttachmentRef
    suspend fun markRead(childId: String, teacherId: String)
    suspend fun sendTyping(childId: String, teacherId: String)
    fun connect()
    fun disconnect()
}

/**
 * R8: fold a `status` frame into a thread list. The frame is additive (R4) and the only way the parent learns that a
 * coordinator resolved — or re-opened — her complaint without refetching, so a row that is not in [threads] is simply
 * not ours and the list comes back unchanged rather than growing a row we know nothing else about.
 */
fun applyStatus(threads: List<ChatThread>, threadId: String, status: ChatThreadStatus, at: Long): List<ChatThread> =
    threads.map { thread ->
        if (thread.id != threadId) thread
        else thread.copy(status = status, resolvedAt = if (status == ChatThreadStatus.RESOLVED) at else null)
    }

/**
 * M4 (D6): fold a T1 `presence` frame about staff member [userId] into the rows she is on. A row whose `peerOnline` was
 * absent stays absent — "nothing to say" (the Admin's support rows) is not turned into a dot by a frame.
 */
fun applyPresence(threads: List<ChatThread>, userId: String, online: Boolean): List<ChatThread> =
    threads.map { if (it.teacherId == userId && it.peerOnline != null) it.copy(peerOnline = online) else it }
