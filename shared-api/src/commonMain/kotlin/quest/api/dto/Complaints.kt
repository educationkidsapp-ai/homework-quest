package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * B6 `backend/complaints-separate` (owner, 2026-10-03: "Complaints must be separate from messages"). A complaint is
 * **its own conversation**, never a message thread relabelled: the parent opens it about one child, to one staff
 * member — a teacher of the child's section, a coordinator of one of its subjects, or the manager of its department —
 * with a short [Complaint.title] and a first message, and it gets a thread of its own beside (never instead of) any
 * Messages conversation with the same person. Messages lists never show a complaint, and the Complaints lists never
 * show a message thread.
 *
 * Messages inside a complaint are ordinary [ChatMessage]s (B5's attachments included) and arrive live on `/ws/chat` as
 * `message` frames named by the complaint's id ([ChatMessage.threadId] == [Complaint.id]); a status change is a
 * `status` frame. Replies, reads and status changes go over REST only — the socket's `message`, `read` and `typing`
 * commands address Messages conversations, not complaints.
 *
 * Routes (all behind the `chat` flag, 404 while a school has it off; `docs/runbook.md` "Complaints" is the contract):
 * - parent: `GET /children/{id}/complaints?status=`, `GET …/complaints/recipients`, `POST …/complaints`,
 *   `GET …/complaints/{complaintId}?before=&since=&limit=`, `POST …/{complaintId}/messages`, `POST …/{complaintId}/read`,
 *   `PATCH …/{complaintId}/status` (`open` only: she may reopen, not resolve).
 * - staff, `{area}` = `teacher` | `coordinator` | `management`: `GET /{area}/complaints?status=`, `GET …/{id}`,
 *   `POST …/{id}/messages`, `POST …/{id}/read`, `PATCH …/{id}/status`.
 * - Admin (support, read-only, `X-School-Id`): `GET /admin/complaints?status=`, `GET /admin/complaints/{id}`.
 */

/**
 * One row of a Complaints list, and the head of a [ComplaintDetail]. [recipientId] / [recipientName] /
 * [recipientRole] name the staff member it was addressed to; [unread] is the caller's own count (always 0 for a
 * supervisor reading somebody else's complaint). [canReply] is true for the two people on the conversation — the
 * parent and the recipient — and false for a supervisor or the Admin, who read it (and, a supervisor, may resolve or
 * reopen it) but do not write in it. [resolvedByName] is who resolved it last, while it is resolved.
 */
@Serializable
data class Complaint(
    val id: String,
    val childId: String,
    val childName: String,
    val title: String,
    val status: ChatThreadStatus,
    val recipientId: String,
    val recipientName: String,
    val recipientRole: ChatPeerRole,
    val createdAt: Long,
    val parentName: String? = null,
    val className: String? = null,
    /**
     * The recipient's subjects on the child's section (a teacher's or a coordinator's) — the words
     * [ComplaintRecipient.subject] showed when the complaint was opened, kept with it; absent for a manager.
     */
    val subject: String? = null,
    val lastMessage: ChatMessage? = null,
    val unread: Int = 0,
    val resolvedAt: Long? = null,
    val resolvedByName: String? = null,
    val canReply: Boolean = false,
    /**
     * Feature 27: what the AI made of this complaint — **staff only**, and absent on every parent endpoint. Also
     * absent while `complaintTriage` is off, before the triage has finished (it runs after the complaint is
     * committed) and whenever the model failed, so a client draws its chips only when there is something to draw.
     */
    val triage: ComplaintTriage? = null,
)

/** Feature 27: what a complaint is about. `OTHER` is the honest answer, not a failure. */
@Serializable
enum class ComplaintCategory {
    @SerialName("academic") ACADEMIC, @SerialName("behaviour") BEHAVIOUR, @SerialName("transport") TRANSPORT,
    @SerialName("fees") FEES, @SerialName("facilities") FACILITIES, @SerialName("staff") STAFF,
    @SerialName("other") OTHER,
}

/** Feature 27: how soon it needs somebody. `HIGH` also rings the department manager once. */
@Serializable
enum class ComplaintUrgency { @SerialName("low") LOW, @SerialName("normal") NORMAL, @SerialName("high") HIGH }

/**
 * Feature 27: the AI's reading of one complaint, written once after it was created and never rewritten. The summaries
 * are two or three lines; [replyAr] / [replyEn] are a **suggestion** — "Use suggested reply" fills the reply box and
 * the member of staff still presses Send, which is the existing `POST /{area}/complaints/{id}/messages`. Nothing here
 * is ever sent, shown to a parent, or treated as a decision.
 */
@Serializable
data class ComplaintTriage(
    val category: ComplaintCategory,
    val urgency: ComplaintUrgency,
    val summaryAr: String,
    val summaryEn: String,
    val replyAr: String,
    val replyEn: String,
)

/** Who moved a complaint: the parent (a reopen) or a staff member (the recipient or a supervisor in scope). */
@Serializable
enum class ComplaintActor { @SerialName("parent") PARENT, @SerialName("staff") STAFF }

/**
 * One status change, recorded with who and when — what a client draws as a system line inside the conversation
 * ("Resolved by Nour · 3 Oct"), interleaved with [ComplaintDetail.messages] by [at]. [byName] is the staff member's
 * name, or the parent's (the name the Admin typed for her, else her address).
 */
@Serializable
data class ComplaintEvent(val status: ChatThreadStatus, val by: ComplaintActor, val byId: String, val byName: String, val at: Long)

/**
 * `GET …/complaints/{id}` and `POST …/complaints`: the complaint, a page of its messages (oldest first, paged with
 * `before` / `since` / `limit` exactly as a Messages thread is) and **every** status change it has had.
 */
@Serializable
data class ComplaintDetail(
    val complaint: Complaint,
    val messages: List<ChatMessage> = emptyList(),
    val events: List<ComplaintEvent> = emptyList(),
    /**
     * Feature 27: when the parent may expect an answer (epoch ms), on **her own** two routes only —
     * `POST /children/{id}/complaints` and `GET /children/{id}/complaints/{complaintId}`. It is the recipient's own
     * office hours (feature 19) in the school's zone: inside them, four hours from now capped at today's closing
     * time; outside them, two hours after her next working period starts. Absent while `chatOfficeHours` is off,
     * when no working day can be found, and on every staff route — it is a promise to the parent, not a deadline the
     * school is measured on. It is **not** part of [Complaint.triage] and does not follow `complaintTriage`.
     */
    val expectedReplyBy: Long? = null,
)

/**
 * `GET …/complaints?status=open|resolved|all` (default `all`): the rows the filter keeps, newest activity first with
 * the caller's unread ones on top, and the counts of **all** her complaints by status whatever the filter — the
 * badges on the Open and Resolved tabs.
 */
@Serializable
data class ComplaintList(val complaints: List<Complaint> = emptyList(), val open: Int = 0, val resolved: Int = 0)

/**
 * `GET /children/{id}/complaints/recipients`: whom she may complain to about this child — every teacher of the
 * child's section, the coordinators of its subjects and the manager of its department. [subject] is the teacher's or
 * coordinator's subjects on that section ("math, science"), absent for a manager. [peerRole] is named as
 * [ChatThread.peerRole] is (and not `role`, which would collide with [Complaint.recipientRole] in a generated client).
 */
@Serializable
data class ComplaintRecipient(val staffId: String, val name: String, val peerRole: ChatPeerRole, val subject: String? = null)

/**
 * `POST /children/{id}/complaints` — [staffId] one of the recipients above (404 for anyone else), [title] 1–120
 * characters, [body] the first message (1–2000 characters, plain text; B5: may be empty when [attachmentIds] names at
 * least one upload). [clientId] comes back on the socket echo of that first message.
 */
@Serializable
data class CreateComplaintRequest(
    val staffId: String,
    val title: String,
    val body: String = "",
    val clientId: String? = null,
    val attachmentIds: List<String> = emptyList(),
)

/**
 * `PATCH …/complaints/{id}/status`. `resolved` from the recipient or a supervisor in scope; `open` (reopen) from the
 * recipient, a supervisor or the parent. Setting the status it already has changes nothing and notifies nobody.
 */
@Serializable
data class ComplaintStatusRequest(val status: ChatThreadStatus)

/** Which dashboard's Complaints routes a [quest.api.dashboard.DashboardApi] call goes to: `/{path}/complaints`. */
@Serializable
enum class ComplaintArea(val path: String) {
    @SerialName("teacher") TEACHER("teacher"), @SerialName("coordinator") COORDINATOR("coordinator"),
    @SerialName("management") MANAGEMENT("management"), @SerialName("admin") ADMIN("admin"),
}
