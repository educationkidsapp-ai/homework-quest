package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * C1 `backend/chat-websocket` — one conversation per (child, teacher) between the child's parent (app) and a
 * teacher assigned to the child's section (dashboard). Plain text only: [ChatMessage.body] is stored and delivered
 * exactly as typed and is never interpreted as HTML by either client.
 *
 * REST carries history and the writes (`/children/{id}/chat/…` for the parent, `/teacher/chat/…` for the teacher);
 * the socket at `/ws/chat` carries the same [ChatMessage] live as a [ChatFrame]. The whole area is behind the `chat`
 * flag: 404 on REST and 403 on the handshake while a school has it off. `docs/runbook.md` "Chat" is the client
 * contract (auth, frames, ack, reconnect).
 */

/** Who wrote a message or is typing: the child's parent, or the teacher. */
@Serializable
enum class ChatSender { @SerialName("parent") PARENT, @SerialName("teacher") TEACHER }

/** One message. [readAt] is set once the other party marked the thread read; times are epoch milliseconds. */
@Serializable
data class ChatMessage(
    val id: String,
    val threadId: String,
    val sender: ChatSender,
    val senderId: String,
    val body: String,
    val createdAt: Long,
    val readAt: Long? = null,
)

/**
 * One row of a thread list. [id] is null until the first message is sent — a parent's list names every teacher of
 * the child's section whether or not anyone has written yet. [unread] is the caller's own unread count.
 */
@Serializable
data class ChatThread(
    val id: String? = null,
    val childId: String,
    val childName: String,
    val teacherId: String,
    val teacherName: String,
    val className: String? = null,
    val subject: String? = null,
    val unread: Int = 0,
    val lastMessage: ChatMessage? = null,
)

/** `POST …/messages`. [clientId] is the client's own id for the send; it comes back in the socket's echo. */
@Serializable
data class SendChatMessageRequest(val body: String, val clientId: String? = null)

/** `POST …/read`: everything the other party wrote is now read, as of [readAt]. */
@Serializable
data class ChatReadReceipt(val threadId: String, val readBy: ChatSender, val readAt: Long)

/**
 * Server → client frames on `/ws/chat`, discriminated by `type`. Validated on the client against
 * `ChatFrame.schema.json` the way `Play.schema.json` is.
 *
 * - [Message]: a message landed in one of the caller's threads. The sender's own sessions receive it too, with
 *   [Message.clientId] echoed from the command that sent it — that echo **is** the ack, and a client dedupes on it.
 * - [Read]: the other party (or the caller from another device) marked a thread read.
 * - [Typing]: the other party is typing. Fan-out only — never stored, and dropped first under backpressure.
 * - [Notification]: D26 — a dashboard notification for the signed-in user. The socket is the dashboard's event
 *   channel, not only its chat: this frame reaches ADMIN, MANAGERIAL and TEACHER whether or not the school has the
 *   `chat` flag on, and never a parent. The same row is readable over `/me/notifications`.
 * - [Ping]: sent every 30 s; answer with a `pong` command (any command counts) or the session is closed as idle
 *   after 10 minutes without one.
 * - [Pong]: the reply to a client `ping`.
 * - [Error]: a command was refused; [Error.clientId] names the send it was about, when it was a send.
 */
@Serializable
sealed class ChatFrame {
    @Serializable @SerialName("message") data class Message(val message: ChatMessage, val clientId: String? = null) : ChatFrame()
    @Serializable @SerialName("read") data class Read(val threadId: String, val readBy: ChatSender, val readAt: Long) : ChatFrame()
    @Serializable @SerialName("typing") data class Typing(val threadId: String, val from: ChatSender) : ChatFrame()
    @Serializable @SerialName("notification") data class Notification(val notification: NotificationView) : ChatFrame()
    @Serializable @SerialName("ping") data object Ping : ChatFrame()
    @Serializable @SerialName("pong") data object Pong : ChatFrame()
    @Serializable @SerialName("error") data class Error(val code: String, val message: String, val clientId: String? = null) : ChatFrame()
}

/**
 * Client → server commands on `/ws/chat`, discriminated by `type`. A parent names the thread by [Send.teacherId]
 * (the child is [Send.childId]); a teacher names it by [Send.childId] alone.
 */
@Serializable
sealed class ChatCommand {
    @Serializable @SerialName("message") data class Send(val childId: String, val teacherId: String? = null, val body: String, val clientId: String? = null) : ChatCommand()
    @Serializable @SerialName("typing") data class Typing(val childId: String, val teacherId: String? = null) : ChatCommand()
    @Serializable @SerialName("read") data class Read(val childId: String, val teacherId: String? = null) : ChatCommand()
    @Serializable @SerialName("ping") data object Ping : ChatCommand()
    @Serializable @SerialName("pong") data object Pong : ChatCommand()
}
