package quest.feature.chat.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread

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
     * [coordinators] answers. B6: a complaint to her is a complaint of its own (`ComplaintsRepository`), never this thread.
     */
    suspend fun managers(childId: String): List<ChatThread>
    suspend fun messages(childId: String, teacherId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>
    /**
     * M7 (B5): [attachmentIds] are uploads from [uploadAttachment], at most five, and [body] may then be empty. B6: a
     * Messages send never carries a topic — a complaint is opened on the Complaints page.
     */
    suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String> = emptyList()): ChatMessage

    /** M7 (B5): one staged photo or PDF for a message about [childId]; [onProgress] runs 0..1 while the bytes go up. */
    suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit = {}): AttachmentRef
    suspend fun markRead(childId: String, teacherId: String)
    suspend fun sendTyping(childId: String, teacherId: String)
    fun connect()
    fun disconnect()
}

/**
 * M4 (D6): fold a T1 `presence` frame about staff member [userId] into the rows she is on. A row whose `peerOnline` was
 * absent stays absent — "nothing to say" (the Admin's support rows) is not turned into a dot by a frame.
 */
fun applyPresence(threads: List<ChatThread>, userId: String, online: Boolean): List<ChatThread> =
    threads.map { if (it.teacherId == userId && it.peerOnline != null) it.copy(peerOnline = online) else it }
