package quest.feature.chat.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic

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
    suspend fun messages(childId: String, teacherId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>
    /** [topic] is read by the server only when this message creates the thread; `complaint` needs a coordinator peer. */
    suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic? = null): ChatMessage
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
