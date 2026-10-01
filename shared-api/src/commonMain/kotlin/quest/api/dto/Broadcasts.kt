package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RM2 `backend/broadcasts` (DR6) — the weekly plan, announcements and events, one feature with two composers and
 * three audiences.
 *
 * A **manager** posts to her department: the parents of its sections, the teachers assigned there, the coordinators
 * whose scope meets it, in any combination ([BroadcastView.audience]). A **coordinator** posts announcements and
 * events to the parents of the classes she coordinates. Nobody names a school, a track or a section that is not
 * already in her own scope — the audience is resolved server-side from `staff_scopes`.
 *
 * Delivery is a pull and a push: `GET /me/broadcasts` for a dashboard user, `GET /children/{id}/broadcasts` for the app,
 * both newest first with an unread count ([BroadcastFeed]), and a `broadcast.posted` notification
 * ([NotificationKind.BROADCAST_POSTED]) on the bell and the `/ws/chat` socket for the dashboard recipients. Parents
 * have no bell and poll their feed. `docs/runbook.md` "Broadcasts" is the client contract.
 */

/**
 * What a broadcast is. A `WEEKLY_PLAN` carries [BroadcastView.weekStart] and there is one per week per department and
 * grade — MG1 added [BroadcastView.grade], so a grade's plan and the department's all-grades plan coexist for a week.
 */
@Serializable
enum class BroadcastKind {
    @SerialName("weekly_plan") WEEKLY_PLAN,
    @SerialName("announcement") ANNOUNCEMENT,
    @SerialName("event") EVENT,
}

/** Who a broadcast is for. A coordinator's is always `PARENTS`; a manager chooses any non-empty subset. */
@Serializable
enum class BroadcastAudience {
    @SerialName("parents") PARENTS,
    @SerialName("teachers") TEACHERS,
    @SerialName("coordinators") COORDINATORS,
}

/**
 * What is attached. MH1 makes it a **reference**: [id] is an `attachments` row uploaded through
 * `POST /media/attachments`, [url] is then `/media/attachments/{id}` — so a client that only knows how to render a URL
 * needs no change — and [type] is the stored media type (`image/jpeg`, `image/png`, `image/webp` or — S1, weekly plans only — `application/pdf`; the server sniffs it
 * from the bytes rather than trusting the upload's header). A row written before MH1 has a [url] the composer typed and
 * no [id].
 */
@Serializable
data class BroadcastAttachment(
    val url: String,
    val name: String? = null,
    /** MH1: the `attachments` row, absent on a row written before MH1 (whose [url] the composer typed by hand). */
    val id: String? = null,
    val type: String? = null,
)

/** MH1: what `POST /media/attachments` answers — the id a composer then sends as [CreateBroadcastRequest.attachmentId]. */
@Serializable
data class AttachmentRef(val id: String, val name: String, val type: String, val sizeBytes: Long = 0)

/**
 * One row of a feed or of a composer's own list. [sectionIds] is empty when the row is the whole department's.
 * [authorRole] with [curriculum] or [subject] is how a client labels the card — a manager speaks for a department
 * ([curriculum] set, [subject] absent) and a coordinator for her subjects ([subject] a comma-separated list of them,
 * [curriculum] absent because her sections say which track they are in). [grade] is the one grade of the
 * department the row is for, and `null` means every grade of it. [read] is the caller's own
 * flag, never the other recipients'. Times are epoch milliseconds; [weekStart] is an ISO date (`2026-09-27`).
 */
@Serializable
data class BroadcastView(
    val id: String,
    val kind: BroadcastKind,
    val authorId: String,
    val authorName: String,
    val authorRole: ChatStaffRole = ChatStaffRole.MANAGERIAL,
    val title: String? = null,
    val bodyEn: String,
    val bodyAr: String? = null,
    val weekStart: String? = null,
    val curriculum: Curriculum? = null,
    val grade: Int? = null,
    val subject: String? = null,
    val sectionIds: List<String> = emptyList(),
    val audience: List<BroadcastAudience> = emptyList(),
    val attachment: BroadcastAttachment? = null,
    val expiresAt: Long? = null,
    val createdAt: Long,
    val read: Boolean = false,
)

/** `GET /me/broadcasts` and `GET /children/{id}/broadcasts`: newest first, with the caller's own unread count. */
@Serializable
data class BroadcastFeed(val unread: Int = 0, val items: List<BroadcastView> = emptyList())

/**
 * `POST /management/broadcasts` and `POST /coordinator/broadcasts`. [sectionIds] empty means the author's whole
 * scope; a section outside it is 403. [audience] is ignored on the coordinator's route, which is always the parents
 * of her classes, and must be non-empty on the manager's. [weekStart] is required for `WEEKLY_PLAN` and refused for
 * the other two kinds; posting the same (week, department, [grade]) again replaces the plan that was there.
 * [grade] narrows the row to one grade of the author's department and may not be combined with [sectionIds], which
 * already say which sections are meant; `null` is every grade.
 *
 * **MH1: a manager's `WEEKLY_PLAN` requires [grade] and [attachmentId].** The owner's plan is "the week and an uploaded
 * image": a plan with no grade (the department-wide plan RM2 allowed) and a plan with no image are both 400, [audience]
 * is ignored — it is always the parents, teachers and coordinators of that grade — and [bodyEn]/[title] are optional,
 * the server writing "Weekly plan · Grade N · week of <date>" when they are absent.
 */
@Serializable
data class CreateBroadcastRequest(
    val kind: BroadcastKind,
    /** Required for an `ANNOUNCEMENT` or an `EVENT`; optional for a `WEEKLY_PLAN`, where the server writes it. */
    val bodyEn: String? = null,
    val title: String? = null,
    val bodyAr: String? = null,
    val weekStart: String? = null,
    val grade: Int? = null,
    val audience: List<BroadcastAudience> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    val attachment: BroadcastAttachment? = null,
    /** MH1: an [AttachmentRef.id] from `POST /media/attachments`, and the author's own upload — anybody else's is 400. */
    val attachmentId: String? = null,
    val expiresAt: Long? = null,
)

// ---- MG1: the archive (`GET /management/weekly-plans`, `GET /me/weekly-plans`, `GET /children/{id}/weekly-plans`)

/**
 * One plan in the archive. [readBy] is how many people have opened it and is answered on the manager's own archive
 * only — the two reader archives carry [BroadcastView.read], her own flag, instead. There is no `audienceSize`
 * beside it: the audience is resolved per reader from `staff_scopes` and counting it would be a statement per row.
 */
@Serializable
data class WeeklyPlanEntry(val plan: BroadcastView, val readBy: Int? = null)

/** One school week, its plans ordered all-grades first then by grade. */
@Serializable
data class WeeklyPlanWeek(val weekStart: String, val items: List<WeeklyPlanEntry> = emptyList())

/**
 * The weekly-plan archive over a window, **newest week first**, including weeks that have already passed — the feeds
 * hide an expired row and the archive never does. [from] and [to] are ISO dates and echo the window that was used:
 * absent parameters mean the last twelve weeks ending this one.
 */
@Serializable
data class WeeklyPlanArchive(
    val from: String,
    val to: String,
    /** MH1: the caller's own unread plans inside the window. A parent has no bell, so this *is* her notification. */
    val unread: Int = 0,
    val weeks: List<WeeklyPlanWeek> = emptyList(),
)
