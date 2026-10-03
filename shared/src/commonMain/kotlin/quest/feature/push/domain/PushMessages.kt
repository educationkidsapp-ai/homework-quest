package quest.feature.push.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.NotificationView
import quest.api.dto.PushMessage

/**
 * The system channels a parent can switch on and off one by one. Ids are stable; names follow the app language.
 * [COMPLAINTS] is its own channel because a complaint's status is something a parent may want loud while she mutes
 * the rest.
 */
enum class PushChannel(val id: String) {
    MESSAGES("messages"), EXAMS("exams"), HOMEWORK("homework"), COMPLAINTS("complaints"), SCHOOL_NEWS("school_news"),
}

/**
 * One tap — on a push, or on a row of the Notifications tab — with everything the router needs to find its page:
 * the kind's wire name, the app path, the row (`notificationId`), the broadcast, the child, and the collapse key, which
 * names the thread, lesson, broadcast, announcement or question (`chat:{id}`, `lesson:{id}`, …).
 */
data class NotificationTap(
    val kind: String,
    val link: String? = null,
    val notificationId: String? = null,
    val broadcastId: String? = null,
    val childId: String? = null,
    val collapseKey: String? = null,
    /** A row's `lessonId` — the lesson, broadcast, announcement, question or thread the row is about. */
    val subjectId: String? = null,
) {
    /** The id after `prefix:` in the collapse key, else the row's own subject id. */
    fun idFor(prefix: String): String? =
        collapseKey?.takeIf { it.startsWith("$prefix:") }?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: subjectId

    companion object {
        fun of(row: NotificationView): NotificationTap {
            val kind = PushMessage.kindName(row.kind)
            return NotificationTap(kind, row.link, row.id, broadcastId = if (kind == "broadcast.posted") row.lessonId else null, childId = row.childId, subjectId = row.lessonId)
        }
    }
}

/**
 * One received push, as the system notification shows it. [title] and [body] are exactly what the server sent — in
 * the language the device registered with — nothing is added or translated; a push with no title at all gets the
 * app's own generic one ([title] null). [tag] is B4's collapse key: a second word about the same thread or lesson
 * replaces the first in the shade rather than stacking under it.
 */
data class PushNotice(
    val channel: PushChannel,
    val tag: String,
    val title: String?,
    val body: String?,
    val tap: NotificationTap,
)

/**
 * B4's FCM data map (`quest.api.dto.PushMessage`), read with the contract's own decoder where it knows the kind, and
 * key by key where it does not: a kind this build has never heard of (the owner, 2026-10-03: "push for any message,
 * event, announcement, anything sent") is still shown — on School news, opening the Notifications tab — never dropped.
 */
object PushPayload {
    fun parse(data: Map<String, String>): PushNotice? {
        if (data.isEmpty()) return null
        val known = PushMessage.fromData(data)
        val kind = known?.let { PushMessage.kindName(it.kind) } ?: data[PushMessage.KIND].orEmpty()
        val title = (known?.title ?: data[PushMessage.TITLE])?.takeIf { it.isNotBlank() }
        val body = (known?.body ?: data[PushMessage.BODY])?.takeIf { it.isNotBlank() }
        val notificationId = known?.notificationId ?: data[PushMessage.NOTIFICATION_ID]
        val broadcastId = known?.broadcastId ?: data[PushMessage.BROADCAST_ID]
        val collapseKey = (known?.collapseKey ?: data[PushMessage.COLLAPSE_KEY])?.takeIf { it.isNotBlank() }
        val tag = collapseKey ?: notificationId?.let { "notification:$it" } ?: broadcastId?.let { "broadcast:$it" } ?: "kind:$kind"
        val tap = NotificationTap(kind, known?.link ?: data[PushMessage.LINK], notificationId, broadcastId, known?.childId ?: data[PushMessage.CHILD_ID], collapseKey)
        return PushNotice(channelOf(kind), tag, title, body, tap)
    }

    /** By the kind's wire name, so a kind added on the server after this build still lands on a sensible channel. */
    fun channelOf(kind: String): PushChannel = when {
        kind.startsWith("complaint.") -> PushChannel.COMPLAINTS
        kind.startsWith("exam.") -> PushChannel.EXAMS
        kind.startsWith("homework.") || kind == "question.sent" -> PushChannel.HOMEWORK
        kind.startsWith("chat.") || kind.endsWith(".message") -> PushChannel.MESSAGES
        else -> PushChannel.SCHOOL_NEWS
    }
}

private val CHILD = Regex("^/children/([^/?#]+)")
private val CHAT = Regex("^/children/[^/]+/chat/([^/?#]+)")
private val OPEN = Regex("[?&]open=([^&#]+)")

/** The app paths B4's contract writes on a row and a push (`ebbab61`). */
enum class LinkShape { CHAT, BROADCAST, ANNOUNCEMENT, TEACHER_QUESTION, MAP, PROGRESS, OTHER }

private val SHAPES = listOf(
    Regex("^/children/[^/]+/chat/[^/?#]+$") to LinkShape.CHAT,
    Regex("^/children/[^/]+/broadcasts(\\?.*)?$") to LinkShape.BROADCAST,
    Regex("^/children/[^/]+/announcements(\\?.*)?$") to LinkShape.ANNOUNCEMENT,
    Regex("^/children/[^/]+/teacher-questions/[^/?#]+$") to LinkShape.TEACHER_QUESTION,
    Regex("^/children/[^/]+/map$") to LinkShape.MAP,
    Regex("^/children/[^/]+/progress$") to LinkShape.PROGRESS,
)

fun linkShape(link: String?): LinkShape = link?.trim()?.let { path -> SHAPES.firstOrNull { it.first.matches(path) }?.second } ?: LinkShape.OTHER

/** The child an app path names (`/children/{id}/…`). */
fun childOf(link: String?): String? = link?.let { CHILD.find(it.trim())?.groupValues?.get(1) }

/** The staff member a chat path names (`/children/{id}/chat/{staffId}`). */
fun staffOf(link: String?): String? = link?.let { CHAT.find(it.trim())?.groupValues?.get(1) }

/** The `?open=` id of a broadcasts or announcements path. */
fun openOf(link: String?): String? = link?.let { OPEN.find(it)?.groupValues?.get(1) }

/**
 * Taps waiting to be followed, like the widget's `TodayLinks`: the app's root collects [pending], so the navigation
 * happens underneath the biometric lock and through the parent gate — never around either. A tap that has to wait for
 * the gate is kept in [awaiting] until the gate opens ([gateOpened]) or the parent turns back ([abandoned]).
 */
object PushLinks {
    private val _pending = MutableStateFlow<NotificationTap?>(null)
    val pending: StateFlow<NotificationTap?> = _pending
    private val _unlocked = MutableStateFlow<NotificationTap?>(null)
    /** The tap to follow now that the gate has opened. */
    val unlocked: StateFlow<NotificationTap?> = _unlocked
    var awaiting: NotificationTap? = null
        private set

    fun open(tap: NotificationTap) { _pending.value = tap }
    fun consumed() { _pending.value = null }
    fun waitForGate(tap: NotificationTap) { awaiting = tap }
    fun gateOpened() { awaiting?.let { _unlocked.value = it }; awaiting = null }
    fun followed() { _unlocked.value = null }
    /** The parent backed out of the gate: the target is never shown. */
    fun abandoned() { awaiting = null }
}
