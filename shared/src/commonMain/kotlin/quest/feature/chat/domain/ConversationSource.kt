package quest.feature.chat.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender

/**
 * Feature 13: **one open conversation, whichever side of it you are on.**
 *
 * `ChatConversationViewModel` and `ChatConversationScreen` were the parent's: keyed by child and teacher, with
 * "mine" meaning `sender == parent`. A staff member opens the same conversation from the other end — her threads are
 * named by id, her sends go to the `/staff/chat` routes, and "mine" is her own user id — so everything that differs between
 * the two ends is named here and nothing else about the screen changes. A second chat UI is exactly what this avoids.
 *
 * The orientation questions ([isMine] and the three `isPeer*`) exist because `/ws/chat` is one socket per person and
 * fans out **every** thread she is on: a frame has to be matched against this conversation and then attributed to a
 * side before it moves a bubble, a tick or the typing line.
 */
interface ConversationSource {
    val connectionState: StateFlow<ChatConnectionState>
    val incomingFrames: SharedFlow<ChatFrame>

    suspend fun messages(before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage>
    suspend fun send(body: String, clientId: String, attachmentIds: List<String>): ChatMessage
    suspend fun markRead()
    suspend fun sendTyping()
    suspend fun upload(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef

    /**
     * This conversation's thread id, looked up from the signed-in person's own rows when the screen opened without
     * one — a thread the other side has just created has an id the socket names it by before anybody has written.
     * Null when there is still no thread.
     */
    suspend fun resolveThreadId(): String?

    fun connect()

    /** Written by the signed-in person: the right-hand bubble, with the delivery ticks. */
    fun isMine(message: ChatMessage): Boolean

    /** A `message` frame from the other end of *this* conversation (the caller has already matched the thread). */
    fun isPeerMessage(message: ChatMessage): Boolean

    /** A `read` frame that acknowledges my messages, rather than my own read of hers echoed back. */
    fun isPeerRead(readBy: ChatSender): Boolean

    /** A `typing` frame from the other end. */
    fun isPeerTyping(from: ChatSender): Boolean

    /** A `presence` frame about the person on the other end, rather than about somebody else on another thread. */
    fun isPeer(userId: String): Boolean
}
