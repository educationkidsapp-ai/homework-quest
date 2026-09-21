package quest.feature.chat.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread

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
    suspend fun messages(childId: String, teacherId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>
    suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String): ChatMessage
    suspend fun markRead(childId: String, teacherId: String)
    suspend fun sendTyping(childId: String, teacherId: String)
    fun connect()
    fun disconnect()
}
