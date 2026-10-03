package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * E2 `backend/notifications` (D26) — the dashboard bell. A notification is written server-side when something a
 * dashboard user was waiting for happened while she was not looking, read back over `/me/notifications` and pushed
 * live as a [ChatFrame.Notification] on `/ws/chat`. Since B3 a parent has rows too, read over the same routes with
 * her Firebase token: a staff member's `chat.message`, `exam.released` and `homework.published`, each with
 * [NotificationView.childId]; their [NotificationView.link] is an app path (`/children/{id}/…`), not a dashboard one.
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
    /**
     * A teacher wrote to her school's coordinator (U1 item 2). [NotificationView.body] is what she
     * wrote, in her own words and her own language, so the dashboard shows the server's body here
     * rather than a translated sentence.
     */
    @SerialName("teacher.message") TEACHER_MESSAGE,
    /**
     * RM2 (DR6): a manager or a coordinator posted a broadcast this user is an audience of. [NotificationView.title]
     * is the broadcast's own title (or its kind, when it has none) and [NotificationView.body] its English body, so
     * the bell shows what was said rather than that something was said; [NotificationView.link] is the recipient's
     * own broadcasts screen. [NotificationView.lessonId] carries the **broadcast's** id on these rows — the field is
     * "the row this is about", and it is what lets a re-posted weekly plan withdraw the bell entries of the plan it
     * replaces. Since B4 a **parent** has one too, once per broadcast, when it reaches one of her children: it names
     * that child, its [NotificationView.link] is `/children/{childId}/broadcasts?open={broadcastId}`, and the broadcast
     * stays readable on `GET /children/{id}/broadcasts` as before.
     */
    @SerialName("broadcast.posted") BROADCAST_POSTED,
    /**
     * T1: a message landed in one of this user's chat threads — a parent's, or a staff thread with a teacher, a
     * coordinator, a manager or the Admin. [NotificationView.title] is "Message from &lt;name&gt;",
     * [NotificationView.body] the first 120 characters of what was written and [NotificationView.link] her own
     * Messages screen opened on that thread. **At most one unread row per thread per recipient**: a second message
     * she has not read yet updates the row she already has rather than adding another, and reading the thread marks
     * it read. [NotificationView.lessonId] carries the **thread's** id on these rows.
     */
    @SerialName("chat.message") CHAT_MESSAGE,
    /**
     * B3 (parents only): the teacher released an exam's results — by hand or by the close-of-window sweep — and the
     * child named by [NotificationView.childId] sat in that class. [NotificationView.lessonId] is the exam's id; the
     * score itself is read from `GET /children/{id}/progress` (`results`), never carried here.
     */
    @SerialName("exam.released") EXAM_RELEASED,
    /**
     * B3 (parents only): a homework was published to the class of [NotificationView.childId] (its results are released
     * with it, §7). [NotificationView.lessonId] is the homework's id. Written once per lesson and child.
     */
    @SerialName("homework.published") HOMEWORK_PUBLISHED,
    /**
     * B4 (parents only): an exam was published to the class of [NotificationView.childId] — its title and its window,
     * never its content. [NotificationView.lessonId] is the exam's id, [NotificationView.link] `/children/{id}/map`;
     * the push also carries the window as [PushMessage.opensAt] / [PushMessage.closesAt]. Once per exam and child.
     */
    @SerialName("exam.published") EXAM_PUBLISHED,
    /**
     * B4 (parents only): a teacher posted a note to the parents of a class her child's course sits in (§6 screen 16,
     * `GET /children/{id}/announcements`). [NotificationView.lessonId] is the announcement's id and
     * [NotificationView.link] `/children/{childId}/announcements?open={id}`. Once per announcement and parent.
     */
    @SerialName("announcement.posted") ANNOUNCEMENT_POSTED,
    /**
     * B4 (parents only): a teacher sent the child a question to answer in the app (§6 screen 14; the "Message pot"
     * island on the map). [NotificationView.lessonId] is the question's id and [NotificationView.link]
     * `/children/{childId}/teacher-questions/{id}`. Once per question and child.
     */
    @SerialName("question.sent") QUESTION_SENT,
    /**
     * B4 (parents only): a coordinator or a manager marked one of her threads resolved, or opened it again —
     * [NotificationView.title] says which. [NotificationView.lessonId] is the **thread's** id and
     * [NotificationView.link] the thread (`/children/{childId}/chat/{staffId}`). One row per change.
     */
    @SerialName("complaint.status") COMPLAINT_STATUS,
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
    /** B3: on a parent's row, the child it is about (a parent may have several); null on every dashboard row. */
    val childId: String? = null,
)

/** `GET /me/notifications/unread-count` — the badge, on its own so the bell need not page the list to draw it. */
@Serializable
data class UnreadCount(val count: Int)
