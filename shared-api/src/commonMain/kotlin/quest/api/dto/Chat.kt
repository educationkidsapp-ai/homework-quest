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

/**
 * Who wrote a message or is typing: the child's parent, or the staff member on the dashboard side. `teacher` is
 * every staff sender, whatever [ChatThread.staffRole] says — a client tells two staff members of a staff-to-staff
 * thread apart by [ChatMessage.senderId], which is the only thing that can distinguish two people of one role.
 */
@Serializable
enum class ChatSender { @SerialName("parent") PARENT, @SerialName("teacher") TEACHER }

/**
 * R4 (DR3): which staff member holds the dashboard side of a thread. `TEACHER` is what every thread written before
 * R4 carries, so a client that ignores this field reads the C1 contract unchanged. `MANAGERIAL` is the staff-to-staff
 * shape — a coordinator and the manager of her department, with no child on it.
 */
@Serializable
enum class ChatStaffRole { @SerialName("TEACHER") TEACHER, @SerialName("COORDINATOR") COORDINATOR, @SerialName("MANAGERIAL") MANAGERIAL }

/** What the parent opened a thread about. A `complaint` is what the coordinator's Complaints inbox lists (DR3). */
@Serializable
enum class ChatTopic { @SerialName("question") QUESTION, @SerialName("complaint") COMPLAINT }

/** Where a thread stands. Only the staff side moves it, and it is the complaint inbox's filter. */
@Serializable
enum class ChatThreadStatus { @SerialName("open") OPEN, @SerialName("resolved") RESOLVED }

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
 *
 * <p>[teacherId] and [teacherName] name the *other* person on a staff-to-staff thread ([staffRole] `MANAGERIAL`),
 * and on that shape [childId] and [childName] are empty strings, because such a thread is about the department
 * rather than about one child. R4 appended the last four fields with defaults, so a C1 client still reads this row.
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
    val staffRole: ChatStaffRole = ChatStaffRole.TEACHER,
    val topic: ChatTopic = ChatTopic.QUESTION,
    val status: ChatThreadStatus = ChatThreadStatus.OPEN,
    val resolvedAt: Long? = null,
    /**
     * RM1 addendum: who wrote, so a Complaints inbox can name the parent rather than only the child. A parent signs in
     * through Firebase and carries no display name, so this is her registered address — falling back to the one a
     * teacher typed on the roster, and absent on a staff-to-staff thread, which has no parent on it at all.
     */
    val parentName: String? = null,
    /**
     * T1: whether the person on the other end is **online right now** — she holds at least one live `/ws/chat`
     * socket. It is a snapshot taken when the row was built; the [ChatFrame.Presence] frame keeps it true while the
     * list is open, and a client that ignores both simply never shows a presence dot.
     */
    val peerOnline: Boolean = false,
)

/**
 * `POST …/messages`. [clientId] is the client's own id for the send; it comes back in the socket's echo. [topic] is
 * read only while the thread is being created by this very message (R4) — a parent marks a conversation a complaint
 * when she opens it, and a later send cannot re-label a thread the coordinator has already worked on.
 */
@Serializable
data class SendChatMessageRequest(val body: String, val clientId: String? = null, val topic: ChatTopic? = null)

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
 * - [Presence]: T1 — somebody who shares a thread with the caller came online or went offline. It is fanned out on
 *   connect and disconnect, so a manager who signs out (or whose tab crashed and missed the heartbeat) stops showing
 *   as "Live" within about a minute rather than forever.
 * - [Status]: R4 — the staff side moved a thread between `open` and `resolved`. Both parties receive it, so the
 *   parent's app can show that her complaint was answered without refetching the list.
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
    @Serializable @SerialName("status") data class Status(val threadId: String, val status: ChatThreadStatus, val at: Long) : ChatFrame()
    /** T1: exactly one of [userId] (a dashboard user) and [parentId] (a parent in the app) names who moved. */
    @Serializable @SerialName("presence") data class Presence(val online: Boolean, val userId: String? = null, val parentId: String? = null) : ChatFrame()
    @Serializable @SerialName("ping") data object Ping : ChatFrame()
    @Serializable @SerialName("pong") data object Pong : ChatFrame()
    @Serializable @SerialName("error") data class Error(val code: String, val message: String, val clientId: String? = null) : ChatFrame()
}

/**
 * Client → server commands on `/ws/chat`, discriminated by `type`. A parent names the thread by [Send.teacherId]
 * (the child is [Send.childId]); a teacher names it by [Send.childId] alone; a coordinator names it by
 * [Send.threadId], because her threads are not all about a child (R4) and one of them has no child at all.
 */
@Serializable
sealed class ChatCommand {
    @Serializable @SerialName("message") data class Send(val childId: String? = null, val teacherId: String? = null, val body: String, val clientId: String? = null, val threadId: String? = null) : ChatCommand()
    @Serializable @SerialName("typing") data class Typing(val childId: String? = null, val teacherId: String? = null, val threadId: String? = null) : ChatCommand()
    @Serializable @SerialName("read") data class Read(val childId: String? = null, val teacherId: String? = null, val threadId: String? = null) : ChatCommand()
    @Serializable @SerialName("ping") data object Ping : ChatCommand()
    @Serializable @SerialName("pong") data object Pong : ChatCommand()
}
