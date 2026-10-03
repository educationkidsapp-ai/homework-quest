package quest.feature.complaints.domain

import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Complaint
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintEvent
import quest.api.dto.ComplaintList
import quest.api.dto.ComplaintRecipient

/**
 * M8 (the owner, 2026-10-03: "Complaints must be separate from messages"), on B6's contract: a complaint is its own
 * conversation about one child, with one staff member, opened with a title and a first message. The parent replies in
 * it and may **reopen** it once resolved; resolving is the staff side's alone (a `resolved` from her is a 403).
 * Everything here is behind the `chat` flag, as the server's routes are.
 */
interface ComplaintsRepository {
    /** [status] `open`, `resolved` or null for all; the counts in the answer cover every complaint whatever the filter. */
    suspend fun list(childId: String, status: String? = null): ComplaintList
    suspend fun recipients(childId: String): List<ComplaintRecipient>
    suspend fun create(childId: String, staffId: String, title: String, body: String, clientId: String, attachmentIds: List<String> = emptyList()): ComplaintDetail
    suspend fun detail(childId: String, complaintId: String, since: String? = null): ComplaintDetail
    suspend fun reply(childId: String, complaintId: String, body: String, clientId: String, attachmentIds: List<String> = emptyList()): ChatMessage
    suspend fun markRead(childId: String, complaintId: String)
    suspend fun reopen(childId: String, complaintId: String): Complaint

    companion object {
        const val TITLE_MAX = 120
        const val BODY_MAX = 2000
    }
}

/** One line of a complaint's conversation: a message, or a status change drawn as a system line between them. */
sealed interface TimelineItem {
    val at: Long
    val key: String

    data class Message(val message: ChatMessage, val pending: Boolean = false, val failed: Boolean = false, val clientId: String? = null) : TimelineItem {
        override val at: Long get() = message.createdAt
        override val key: String get() = clientId ?: message.id
    }

    data class Event(val event: ComplaintEvent) : TimelineItem {
        override val at: Long get() = event.at
        override val key: String get() = "event-${event.status.name}-${event.at}"
    }
}

/**
 * The messages and the status changes, oldest first, interleaved by time. An event at the same instant as a message
 * goes after it — a staff member answers, then resolves.
 */
fun timeline(messages: List<TimelineItem.Message>, events: List<ComplaintEvent>): List<TimelineItem> =
    (messages + events.map { TimelineItem.Event(it) }).sortedWith(compareBy<TimelineItem> { it.at }.thenBy { if (it is TimelineItem.Event) 1 else 0 })

/**
 * Folds a `status` frame into a list: a row that is not in [complaints] is not ours (the socket carries every thread of
 * hers) and the list comes back unchanged.
 */
fun applyStatus(complaints: List<Complaint>, complaintId: String, status: ChatThreadStatus, at: Long): List<Complaint> =
    complaints.map {
        if (it.id != complaintId) it
        else it.copy(status = status, resolvedAt = if (status == ChatThreadStatus.RESOLVED) at else null, resolvedByName = if (status == ChatThreadStatus.RESOLVED) it.resolvedByName else null)
    }

/** The parent may reopen a resolved complaint; she never resolves one. */
fun canReopen(complaint: Complaint): Boolean = complaint.status == ChatThreadStatus.RESOLVED

/** Whom the new-complaint picker groups a recipient under. */
enum class RecipientGroup { TEACHER, COORDINATOR, MANAGER }

fun groupOf(role: ChatPeerRole): RecipientGroup = when (role) {
    ChatPeerRole.COORDINATOR -> RecipientGroup.COORDINATOR
    ChatPeerRole.MANAGERIAL, ChatPeerRole.ADMIN -> RecipientGroup.MANAGER
    else -> RecipientGroup.TEACHER
}

/** The app path B6 writes on a complaint's rows and pushes: `/children/{childId}/complaints/{complaintId}`. */
private val COMPLAINT_LINK = Regex("^/children/([^/?#]+)/complaints/([^/?#]+)$")

fun complaintOf(link: String?): Pair<String, String>? =
    link?.trim()?.let { COMPLAINT_LINK.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }
