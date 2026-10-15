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
     * B4: a coordinator or a manager marked one of her threads resolved, or opened it again — [NotificationView.title]
     * says which. One row per real change, and [NotificationView.lessonId] is the **complaint's** id.
     *
     * B6: complaints are their own conversations ([Complaint]). A parent's row is written when a staff member resolves
     * or reopens her complaint, linked to `/children/{childId}/complaints/{complaintId}`; since B6 a **dashboard** user
     * has one too — the recipient, when the parent reopens it or a supervisor moves it — linked to her own Complaints
     * page (`/teacher/complaints?open={id}`, `/coordinator/…`, `/management/…`).
     */
    @SerialName("complaint.status") COMPLAINT_STATUS,
    /**
     * B6 (dashboard only): a parent opened a complaint addressed to this user. [NotificationView.title] is "New
     * complaint from &lt;parent&gt;", [NotificationView.body] the complaint's title, [NotificationView.lessonId] the
     * complaint's id and [NotificationView.link] her Complaints page opened on it (`/{area}/complaints?open={id}`).
     */
    @SerialName("complaint.new") COMPLAINT_NEW,
    /**
     * B6: a message landed in a complaint — the staff recipient's reply on the parent's row (with
     * [NotificationView.childId], linked to `/children/{childId}/complaints/{complaintId}`, and pushed), the parent's
     * on the recipient's (linked to `/{area}/complaints?open={id}`). T1's throttle applies: at most one unread row per
     * complaint per recipient, refreshed by later messages and marked read when she reads the complaint.
     * [NotificationView.lessonId] is the complaint's id. A `chat.message` is never written for a complaint.
     */
    @SerialName("complaint.message") COMPLAINT_MESSAGE,
    /**
     * Feature 16, to the employee herself: no check-in at start time, no check-out at end time, or still none at end
     * + the missing-check-out threshold. Tapping opens her Attendance tab. [NotificationView.link] carries the
     * reminder ([AttendanceLink]); the clients build the sentence in her language from it.
     */
    @SerialName("attendance.reminder") ATTENDANCE_REMINDER,
    /**
     * Feature 16, to a leader: an employee is late, has not arrived, left early or did not check out — the direct
     * manager at once, the academy director and the Admin when their policy is `IMMEDIATE`. [NotificationView.link]
     * names the type, the employee and the date ([AttendanceLink]), so feature 17 can open her day.
     */
    @SerialName("attendance.alert") ATTENDANCE_ALERT,
    /**
     * Feature 16: the morning summary for the academy director (her academy) and the Admin (the whole school), at the
     * digest time from feature 14's settings. [NotificationView.link] carries the counts; the body lists the names.
     */
    @SerialName("attendance.digest") ATTENDANCE_DIGEST,
    /**
     * Feature 18, to whoever decides: an employee asked for a remote day or a correction. [NotificationView.link]
     * carries [AttendanceLink.REQUEST] (the request's id), so the Android push can offer Approve and Reject.
     */
    @SerialName("attendance.request") ATTENDANCE_REQUEST,
    /** Feature 18, to the employee: her request was approved or rejected ([AttendanceLink.DECISION]). */
    @SerialName("attendance.decision") ATTENDANCE_DECISION,
    /** Feature 18, to everyone in scope: a school-wide or academy remote day, sent the evening before or at once. */
    @SerialName("attendance.remote_day") ATTENDANCE_REMOTE_DAY,
    /**
     * Feature 19, to a member of staff when her working day starts: how many parent messages reached her outside
     * office hours. [NotificationView.link] is her Messages; the count is the query parameter `count`, so both clients
     * word the row in the reader's language.
     */
    @SerialName("chat.after_hours_summary") CHAT_AFTER_HOURS_SUMMARY,
    /**
     * Feature 25, to an Admin or a department manager each school morning: her brief is ready.
     * [NotificationView.link] is her Home, where `GET /me/morning-brief` is read; the row carries no number of its
     * own, so the lines are never duplicated into a push that cannot be kept in step with the brief.
     */
    @SerialName("morning.brief") MORNING_BRIEF,

    /**
     * Feature 28 (parents only): the evening card about one child's day is ready. [NotificationView.title] is "Today
     * with &lt;her name&gt;" — the **push** may name her, because nothing of her ever reached the model that wrote the
     * card ([EveningCard]) — [NotificationView.childId] is that child and [NotificationView.link]
     * `/children/{childId}/evening-card`. Once per child and day.
     */
    @SerialName("evening.card") EVENING_CARD,
}

/**
 * Feature 16: the query of an attendance notification's link, `/notifications?attendance=<type>&…`. The bell opens the
 * inbox until feature 17's board exists, and both clients read these parameters to word the row in the reader's
 * language: [TYPE] is one of the constants below, [NAME] the employee's name, [MINUTES] the minutes late or early,
 * [STAFF] `<kind>:<id>` and [DATE] `yyyy-MM-dd` in the school's zone. A digest carries [LATE], [NOT_ARRIVED] and
 * [REMOTE] counts instead, with the names in [LATE_NAMES] and [NOT_ARRIVED_NAMES], separated by [NAME_SEPARATOR].
 */
object AttendanceLink {
    const val TYPE = "attendance"
    const val STAFF = "staff"
    const val NAME = "name"
    const val DATE = "date"
    const val MINUTES = "minutes"
    const val LATE = "late"
    const val NOT_ARRIVED = "notArrived"
    const val REMOTE = "remote"
    const val LATE_NAMES = "lateNames"
    const val NOT_ARRIVED_NAMES = "notArrivedNames"
    const val NAME_SEPARATOR = "|"

    /** Reminders, to the employee. */
    const val START_REMINDER = "start_reminder"
    const val END_REMINDER = "end_reminder"
    const val MISSING_CHECKOUT_REMINDER = "missing_checkout_reminder"

    /** Alerts, to a leader. */
    const val ALERT_LATE = "late"
    const val ALERT_NOT_ARRIVED = "not_arrived"
    const val ALERT_EARLY_LEAVE = "early_leave"
    const val ALERT_MISSING_CHECKOUT = "missing_checkout"

    const val DIGEST = "digest"

    /** Feature 18: the request's id, its kind ([REQUEST_REMOTE_DAY] or [REQUEST_CORRECTION]) and the decision. */
    const val REQUEST = "request"
    const val DECISION = "decision"
    const val REQUEST_REMOTE_DAY = "remote_day_request"
    const val REQUEST_CORRECTION = "correction_request"
    const val DECIDED_REMOTE_DAY = "remote_day_decided"
    const val DECIDED_CORRECTION = "correction_decided"
    const val REMOTE_DAY = "remote_day"
    const val APPROVED = "approved"
    const val REJECTED = "rejected"

    /** The query parameters of [link], decoded; empty when it has none. */
    fun params(link: String?): Map<String, String> {
        val query = link?.substringAfter('?', "") ?: return emptyMap()
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=')
            if (key.isEmpty()) null else key to decode(pair.substringAfter('=', ""))
        }.toMap()
    }

    private fun decode(value: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '+' -> { bytes += ' '.code.toByte(); i++ }
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) { bytes += c.code.toByte(); i++ } else { bytes += hex.toByte(); i += 3 }
                }
                else -> { bytes += c.toString().encodeToByteArray().toList(); i++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
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
