package quest.feature.push.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.dto.ChatThread
import quest.api.dto.NotificationKind
import quest.api.dto.PushMessage
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.core.runCancellable
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.notifications.domain.NotificationsRepository

/** The four system channels a parent can switch on and off one by one. Ids are stable; names follow the app language. */
enum class PushChannel(val id: String) { MESSAGES("messages"), EXAM_RESULTS("exam_results"), HOMEWORK("homework"), SCHOOL_NEWS("school_news") }

/**
 * One received push, as the system notification shows it. [title] and [body] are exactly what the server sent — in
 * the language the device registered with — nothing is added, translated or looked up. [tag] is B4's collapse key
 * (`chat:{threadId}`, `lesson:{lessonId}`, `broadcast:{broadcastId}`): a second message in the same thread, or a second
 * word about the same lesson, replaces the first in the shade rather than stacking under it.
 */
data class PushNotice(
    val channel: PushChannel,
    val tag: String,
    val title: String,
    val body: String?,
    val open: PushOpen,
)

/** B4's FCM data map (`quest.api.dto.PushMessage`), read with the contract's own decoder, as the shade shows it. */
object PushPayload {
    fun parse(data: Map<String, String>): PushNotice? {
        val message = PushMessage.fromData(data) ?: return null          // not one of ours: nothing is shown
        return PushNotice(channelOf(message.kind), message.collapseKey, message.title, message.body, PushOpen(message.link, message.notificationId, message.broadcastId))
    }

    fun channelOf(kind: NotificationKind): PushChannel = when (kind) {
        NotificationKind.CHAT_MESSAGE -> PushChannel.MESSAGES
        NotificationKind.EXAM_RELEASED -> PushChannel.EXAM_RESULTS
        NotificationKind.HOMEWORK_PUBLISHED -> PushChannel.HOMEWORK
        else -> PushChannel.SCHOOL_NEWS
    }
}

/** Where a push's `link` leads. Anything unknown — or no link — is the parent home. */
sealed interface PushTarget {
    data class Conversation(val childId: String, val staffId: String) : PushTarget
    data class Progress(val childId: String) : PushTarget
    data class ChildHome(val childId: String) : PushTarget
    /** A weekly plan, announcement or event: the parent's Notifications tab. */
    data class Broadcasts(val childId: String) : PushTarget
    data object ParentHome : PushTarget
}

val PushTarget.childId: String? get() = when (this) {
    is PushTarget.Conversation -> childId
    is PushTarget.Progress -> childId
    is PushTarget.ChildHome -> childId
    is PushTarget.Broadcasts -> childId
    PushTarget.ParentHome -> null
}

private val CHAT = Regex("^/children/([^/]+)/chat/([^/?#]+)$")
private val PROGRESS = Regex("^/children/([^/]+)/progress$")
private val MAP = Regex("^/children/([^/]+)/map$")
private val BROADCASTS = Regex("^/children/([^/]+)/broadcasts(\\?.*)?$")

fun pushTarget(link: String?): PushTarget {
    val path = link?.trim().orEmpty()
    CHAT.find(path)?.let { return PushTarget.Conversation(it.groupValues[1], it.groupValues[2]) }
    PROGRESS.find(path)?.let { return PushTarget.Progress(it.groupValues[1]) }
    MAP.find(path)?.let { return PushTarget.ChildHome(it.groupValues[1]) }
    BROADCASTS.find(path)?.let { return PushTarget.Broadcasts(it.groupValues[1]) }
    return PushTarget.ParentHome
}

/** A tapped notification: its link, and the row it stands for — a notification, or a broadcast — marked read when followed. */
data class PushOpen(val link: String?, val notificationId: String? = null, val broadcastId: String? = null)

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

    fun open(open: PushOpen) { if (open.link != null || open.notificationId != null) _pending.value = open }
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
        val target = pushTarget(open.link)
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
