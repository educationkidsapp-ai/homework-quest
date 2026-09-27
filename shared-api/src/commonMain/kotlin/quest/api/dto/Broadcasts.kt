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

/** What a broadcast is. A `WEEKLY_PLAN` carries [BroadcastView.weekStart] and there is one per week per department. */
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
 * Bytes that already exist somewhere the recipient can read: a `/media/…` path or an absolute URL. RM2 adds no
 * upload route of its own — the composer attaches something it has already uploaded.
 */
@Serializable
data class BroadcastAttachment(val url: String, val name: String? = null)

/**
 * One row of a feed or of a composer's own list. [sectionIds] is empty when the row is the whole department's.
 * [authorRole] with [curriculum] or [subject] is how a client labels the card — a manager speaks for a department
 * ([curriculum] set, [subject] absent) and a coordinator for her subjects ([subject] a comma-separated list of them,
 * [curriculum] absent because her sections say which track they are in). [read] is the caller's own
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
 * the other two kinds; posting the same week again replaces the plan that was there.
 */
@Serializable
data class CreateBroadcastRequest(
    val kind: BroadcastKind,
    val bodyEn: String,
    val title: String? = null,
    val bodyAr: String? = null,
    val weekStart: String? = null,
    val audience: List<BroadcastAudience> = emptyList(),
    val sectionIds: List<String> = emptyList(),
    val attachment: BroadcastAttachment? = null,
    val expiresAt: Long? = null,
)
