package quest.feature.push.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.ChatThread
import quest.api.dto.PushMessage
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.core.runCancellable
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.notifications.domain.NotificationsRepository

/**
 * The system channels a parent can switch on and off one by one. Ids are stable; names follow the app language.
 * [COMPLAINTS] is its own channel because a complaint's status is something a parent may want loud while she mutes
 * the rest.
 */
enum class PushChannel(val id: String) {
    MESSAGES("messages"), EXAMS("exams"), HOMEWORK("homework"), COMPLAINTS("complaints"), SCHOOL_NEWS("school_news"),
}

/**
 * One received push, as the system notification shows it. [title] and [body] are exactly what the server sent — in
 * the language the device registered with — nothing is added or translated; a push with no title at all gets the
 * app's own generic one ([title] null). [tag] is B4's collapse key (`chat:{threadId}`, `lesson:{lessonId}`,
 * `broadcast:{broadcastId}`): a second word about the same thread or lesson replaces the first in the shade.
 */
data class PushNotice(
    val kind: String,
    val channel: PushChannel,
    val tag: String,
    val title: String?,
    val body: String?,
    val open: PushOpen,
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
        val tag = (known?.collapseKey ?: data[PushMessage.COLLAPSE_KEY])?.takeIf { it.isNotBlank() }
            ?: notificationId?.let { "notification:$it" } ?: broadcastId?.let { "broadcast:$it" } ?: "kind:$kind"
        val open = PushOpen(known?.link ?: data[PushMessage.LINK], notificationId, broadcastId, known?.childId ?: data[PushMessage.CHILD_ID])
        return PushNotice(kind, channelOf(kind), tag, title, body, open)
    }

    /** By the kind's wire name, so a kind added on the server after this build still lands on a sensible channel. */
    fun channelOf(kind: String): PushChannel = when {
        kind.startsWith("complaint.") -> PushChannel.COMPLAINTS
        kind.startsWith("exam.") -> PushChannel.EXAMS
        kind.startsWith("homework.") -> PushChannel.HOMEWORK
        kind.startsWith("chat.") || kind.endsWith(".message") -> PushChannel.MESSAGES
        else -> PushChannel.SCHOOL_NEWS
    }
}

/**
 * Where a push's `link` leads. A link this build cannot read — or none — is the Notifications tab, where every row
 * the server wrote is listed. [ParentHome] is only ever the answer for a link that leads nowhere any more.
 */
sealed interface PushTarget {
    data class Conversation(val childId: String, val staffId: String) : PushTarget
    data class Progress(val childId: String) : PushTarget
    data class ChildHome(val childId: String) : PushTarget
    /** A weekly plan, announcement or event, or anything unknown: the parent's Notifications tab. */
    data class Notifications(val childId: String?) : PushTarget
    data object ParentHome : PushTarget
}

val PushTarget.childId: String? get() = when (this) {
    is PushTarget.Conversation -> childId
    is PushTarget.Progress -> childId
    is PushTarget.ChildHome -> childId
    is PushTarget.Notifications -> childId
    PushTarget.ParentHome -> null
}

private val CHAT = Regex("^/children/([^/]+)/chat/([^/?#]+)$")
private val PROGRESS = Regex("^/children/([^/]+)/progress$")
private val MAP = Regex("^/children/([^/]+)/map$")
private val BROADCASTS = Regex("^/children/([^/]+)/broadcasts(\\?.*)?$")

/** The path a gate carries when the push had no link of its own: the Notifications tab. */
const val NOTIFICATIONS_PATH = "/notifications"

fun pushTarget(link: String?, childId: String? = null): PushTarget {
    val path = link?.trim().orEmpty()
    CHAT.find(path)?.let { return PushTarget.Conversation(it.groupValues[1], it.groupValues[2]) }
    PROGRESS.find(path)?.let { return PushTarget.Progress(it.groupValues[1]) }
    MAP.find(path)?.let { return PushTarget.ChildHome(it.groupValues[1]) }
    BROADCASTS.find(path)?.let { return PushTarget.Notifications(it.groupValues[1]) }
    return PushTarget.Notifications(childId)
}

/**
 * A tapped notification: its link, the row it stands for — a notification, or a broadcast — marked read when followed,
 * and the child it is about (which decides whose Notifications tab opens when there is no link).
 */
data class PushOpen(val link: String?, val notificationId: String? = null, val broadcastId: String? = null, val childId: String? = null)

/**
 * Taps waiting to be followed, like the widget's `TodayLinks`: the app's root collects them, so the navigation happens
 * underneath the biometric lock and through the parent area's gate — never around either. [pending] is a tap not yet
 * followed; [afterGate] is the link the parent area follows once its gate has opened.
 */
object PushLinks {
    private val _pending = MutableStateFlow<PushOpen?>(null)
    val pending: StateFlow<PushOpen?> = _pending
    private val _afterGate = MutableStateFlow<String?>(null)
    val afterGate: StateFlow<String?> = _afterGate

    fun open(open: PushOpen) { _pending.value = open }
    fun consumed() { _pending.value = null }
    fun unlocked(link: String) { _afterGate.value = link }
    fun followed() { _afterGate.value = null }
}

/**
 * Following a tap. [prepare] runs before the gate: it marks the row read, and makes the child the link names the
 * current one — or answers [PushTarget.ParentHome] when she is not this parent's child any more. [thread] runs after
 * the gate: the conversation's row, or null when the thread is gone, and the parent stays on her home without an error.
 */
class FollowPushUseCase(
    private val children: ChildrenRepository,
    private val chat: ChatRepository,
    private val notifications: NotificationsRepository,
    private val broadcasts: BroadcastsRepository,
) {
    suspend fun prepare(open: PushOpen): PushTarget {
        val target = pushTarget(open.link, open.childId)
        open.notificationId?.let { id -> runCancellable { notifications.markRead(id) } }
        val childId = target.childId ?: return target
        open.broadcastId?.let { id -> runCancellable { broadcasts.markRead(childId, id) } }
        val known = runCancellable { children.children() }.getOrDefault(emptyList()).any { it.id == childId } ||
            runCancellable { children.refresh() }.getOrDefault(emptyList()).any { it.id == childId }
        if (!known) return PushTarget.ParentHome
        if (children.currentChild.value?.id != childId) runCancellable { children.select(childId) }
        return target
    }

    suspend fun thread(target: PushTarget.Conversation): ChatThread? =
        runCancellable { chat.threads(target.childId) }.getOrNull()?.firstOrNull { it.teacherId == target.staffId }
}
