package quest.feature.push.domain

import quest.api.dto.BroadcastKind
import quest.api.dto.Child
import quest.api.dto.ChatThread
import quest.core.runCancellable
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.chat.domain.ChatRepository
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.feature.children.domain.ChildrenRepository
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.domain.complaintOf
import quest.feature.notifications.domain.NotificationsRepository

/** The page a tap opens — always the specific one, never a generic list (the owner, 2026-10-03). */
sealed interface Destination {
    /** `chat.message`: the Messages thread itself. */
    data class Conversation(val thread: ChatThread) : Destination
    /** M8: `complaint.message` and `complaint.status` — that complaint's own page, about [childId]. */
    data class Complaint(val childId: String, val complaintId: String) : Destination
    /** A weekly plan: the Weekly plan page, on that plan. */
    data class WeeklyPlan(val planId: String) : Destination
    /** An announcement or event from a manager or coordinator: opened on the Notifications tab. */
    data class Broadcast(val broadcastId: String) : Destination
    /** `homework.published`: the parent's view of that lesson (the lesson panel) for that child. */
    data class Lesson(val lessonId: String) : Destination
    /** `exam.published` (or any `…/map` link): the child's home, where the exam's card is — title and window, never the paper. */
    data object ExamCard : Destination
    /** The parent's Progress page — an `…/progress` link that names no exam. */
    data object Progress : Destination
    /** `exam.released`: the parent's result for that exam, with its score (Progress, that result first). */
    data class ExamResult(val lessonId: String) : Destination
    /**
     * The Notifications tab with [row] opened and highlighted — a kind this build does not know, a teacher's class note
     * or question (the app has no page of their own yet), or [gone]: the target no longer exists, said in one line.
     */
    data class Notifications(val row: String?, val gone: Boolean = false) : Destination
}

/**
 * M5 — the one router behind a tapped push and a tapped row of the Notifications tab. It runs only after the parent
 * gate has opened; it marks the row read, switches to the child the tap is about, and finds the page. A target that no
 * longer exists — a deleted thread, an expired event, a lesson gone from the map — opens the Notifications tab with a
 * short message instead of an error.
 */
class NotificationRouter(
    private val children: ChildrenRepository,
    private val chat: ChatRepository,
    private val notifications: NotificationsRepository,
    private val broadcasts: BroadcastsRepository,
    private val complaints: ComplaintsRepository,
    /** The lesson ids on the child's map (the home page's own window), or null when it cannot be read just now. */
    private val lessonsOnMap: suspend (Child) -> Set<String>?,
) {
    suspend fun resolve(tap: NotificationTap): Destination {
        tap.notificationId?.let { id -> runCancellable { notifications.markRead(id) } }
        val childId = tap.childId ?: childOf(tap.link)
        val child = childId?.let { select(it) }
        if (childId != null && child == null) return Destination.Notifications(tap.notificationId, gone = true)
        return when (tap.kind) {
            "chat.message" -> conversation(tap, child)
            "complaint.message", "complaint.status", "complaint.new" -> complaint(tap, child)
            "broadcast.posted" -> broadcast(tap, child)
            "homework.published" -> lesson(tap, child) { Destination.Lesson(it) }
            "exam.published" -> lesson(tap, child) { Destination.ExamCard }
            "exam.released" -> tap.idFor("lesson")?.let { Destination.ExamResult(it) } ?: Destination.Progress
            "announcement.posted", "question.sent" -> notifications(tap)
            else -> byLink(tap, child)                  // a kind still to come: its link decides
        }
    }

    /**
     * A kind this build does not know is followed by the shape of its link — every shape B4's contract writes — and,
     * where the link names nothing this app can open by itself, by its row on the Notifications tab.
     */
    private suspend fun byLink(tap: NotificationTap, child: Child?): Destination = when (linkShape(tap.link)) {
        LinkShape.CHAT -> conversation(tap, child)
        LinkShape.COMPLAINT -> complaint(tap, child)
        LinkShape.BROADCAST -> broadcast(tap, child)
        LinkShape.MAP -> Destination.ExamCard
        LinkShape.PROGRESS -> Destination.Progress
        LinkShape.ANNOUNCEMENT, LinkShape.TEACHER_QUESTION, LinkShape.OTHER -> notifications(tap)
    }

    /** Makes [id] the current child when she is this parent's; null when she is not (any more). */
    private suspend fun select(id: String): Child? {
        val known = runCancellable { children.children() }.getOrDefault(emptyList()).firstOrNull { it.id == id }
            ?: runCancellable { children.refresh() }.getOrDefault(emptyList()).firstOrNull { it.id == id }
            ?: return null
        if (children.currentChild.value?.id != id) runCancellable { children.select(id) }
        return known
    }

    private suspend fun conversation(tap: NotificationTap, child: Child?): Destination {
        val staffId = staffOf(tap.link)
        val threadId = tap.idFor("chat")
        val threads = child?.let { c ->
            listOf<suspend () -> List<ChatThread>>({ chat.threads(c.id) }, { chat.coordinators(c.id) }, { chat.managers(c.id) })
                .flatMap { read -> runCancellable { read() }.getOrDefault(emptyList()) }
        }.orEmpty()
        val thread = threads.firstOrNull { threadId != null && it.id == threadId } ?: threads.firstOrNull { staffId != null && it.teacherId == staffId }
        return thread?.let { Destination.Conversation(it) } ?: notifications(tap, gone = true)
    }

    /**
     * M8: the complaint B6 names three ways — the push's `complaintId`, the `complaint:{id}` collapse key (or a row's
     * `lessonId`), and the link. A row written before B6 may still link a chat thread; it is followed as one. Only a
     * 404 says the complaint is gone — any other failure still opens its page, which says so itself.
     */
    private suspend fun complaint(tap: NotificationTap, child: Child?): Destination {
        val id = tap.complaintId ?: complaintOf(tap.link)?.second ?: tap.idFor("complaint")
            ?: return if (linkShape(tap.link) == LinkShape.CHAT) conversation(tap, child) else notifications(tap)
        child ?: return notifications(tap, gone = true)
        val missing = runCancellable { complaints.detail(child.id, id) }.exceptionOrNull()
            .let { (it as? ApiException)?.error?.code == ApiError.NOT_FOUND }
        return if (missing) notifications(tap, gone = true) else Destination.Complaint(child.id, id)
    }

    private suspend fun broadcast(tap: NotificationTap, child: Child?): Destination {
        val id = tap.broadcastId ?: tap.idFor("broadcast") ?: openOf(tap.link) ?: return notifications(tap)
        child ?: return notifications(tap, gone = true)
        runCancellable { broadcasts.markRead(child.id, id) }
        val inFeed = runCancellable { broadcasts.feed(child.id).items }.getOrNull()?.firstOrNull { it.id == id }
        if (inFeed != null) return if (inFeed.kind == BroadcastKind.WEEKLY_PLAN) Destination.WeeklyPlan(id) else Destination.Broadcast(id)
        // The feed drops an expired row; the archive keeps every plan of the last twelve weeks.
        val plan = runCancellable { broadcasts.plans(child.id).weeks.flatMap { it.items } }.getOrNull()?.firstOrNull { it.plan.id == id }
        return if (plan != null) Destination.WeeklyPlan(id) else notifications(tap, gone = true)
    }

    private suspend fun lesson(tap: NotificationTap, child: Child?, to: (String) -> Destination): Destination {
        val id = tap.idFor("lesson") ?: return notifications(tap)
        val onMap = child?.let { lessonsOnMap(it) }
        return if (onMap != null && id !in onMap) notifications(tap, gone = true) else to(id)
    }

    private fun notifications(tap: NotificationTap, gone: Boolean = false) = Destination.Notifications(tap.notificationId, gone)
}

/**
 * M5 — the parent gate's memory: every tap (push or list) passes the gate first unless it was passed a moment ago.
 * It stays open while the parent is in the parent area, and for [graceMillis] after the app left the foreground — the
 * same minute after which the biometric lock comes back (`AppLock.BACKGROUND_LIMIT_MILLIS`). Going to the child's side
 * closes it at once: the child's home can never open a parent page through a notification.
 */
class ParentGate(private val elapsed: () -> Long, private val graceMillis: Long = 60_000L) {
    private var open = false
    private var awaySince: Long? = null

    val isOpen: Boolean get() = open && awaySince.let { it == null || elapsed() - it <= graceMillis }

    /** The PIN or the biometric confirmed the parent. */
    fun passed() { open = true; awaySince = null }

    /** The child's side is on screen, or nobody is signed in. */
    fun closed() { open = false; awaySince = null }

    /** The app left the foreground. */
    fun away() { if (open && awaySince == null) awaySince = elapsed() }

    /** Back in front: within the grace the gate is still open, after it closed. */
    fun back() { if (!isOpen) closed(); awaySince = null }
}
