package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * E2 `backend/notifications` (D26) — the dashboard bell. A notification is written server-side when something a
 * dashboard user was waiting for happened while she was not looking, read back over `/me/notifications` and pushed
 * live as a [ChatFrame.Notification] on `/ws/chat`. Parents have none: they have the app.
 *
 * The rows belong to one user; a caller only ever reads and writes her own, and another user's id is 404 rather
 * than 403 — the row is not hers to know about. `docs/runbook.md` "Chat and notifications" is the client contract.
 */

/** Why a notification was written. The dashboard localises from this; [NotificationView.title] is the English fallback. */
@Serializable
enum class NotificationKind {
    /** The analysis is done and the skills are waiting to be confirmed before the questions can be written. */
    @SerialName("lesson.needs_skills") LESSON_NEEDS_SKILLS,
    /** The questions are written: the lesson reached Review. */
    @SerialName("lesson.ready") LESSON_READY,
    /** Generation stopped — an error or a paused retry. [NotificationView.body] is the reason. */
    @SerialName("lesson.failed") LESSON_FAILED,
}

/**
 * One row of the bell. [link] is a **dashboard path** (`/teacher/lessons/{id}`), never a URL, so the same row reads
 * the same on QA and in production; [readAt] is null until the user opened it. Times are epoch milliseconds.
 */
@Serializable
data class NotificationView(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String? = null,
    val link: String? = null,
    val lessonId: String? = null,
    val readAt: Long? = null,
    val createdAt: Long,
)

/** `GET /me/notifications/unread-count` — the badge, on its own so the bell need not page the list to draw it. */
@Serializable
data class UnreadCount(val count: Int)
