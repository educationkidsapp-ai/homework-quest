package quest.feature.content.data

import quest.api.ApiException
import quest.api.dto.ChatAttachment
import quest.api.dto.ApiError
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Complaint
import quest.api.dto.ComplaintActor
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintEvent
import quest.api.dto.ComplaintList
import quest.api.dto.ComplaintRecipient
import quest.api.dto.CreateComplaintRequest
import quest.api.dto.SendChatMessageRequest
import quest.core.platform.Ids

/**
 * M8 — B6's parent routes without a server, so the Complaints tab runs on the fake build: the same recipients as the
 * fake's New message (two teachers, two coordinators, the department manager), one resolved and one open complaint
 * seeded per child, and the server's rules — a recipient outside the list is 404, the parent may only reopen.
 */
internal class FakeComplaints(
    private val parentId: suspend () -> String,
    private val now: () -> Long,
    /** The fake's own chat uploads (M7), by id — the same store a Messages send reads. */
    private val upload: (String) -> ChatAttachment?,
) {
    private data class Entry(val complaint: Complaint, val messages: MutableList<ChatMessage>, val events: MutableList<ComplaintEvent>)

    private val byChild = mutableMapOf<String, MutableList<Entry>>()

    private fun attached(ids: List<String>?) = ids.orEmpty().mapNotNull(upload).ifEmpty { null }

    fun recipients(): List<ComplaintRecipient> = listOf(
        ComplaintRecipient("t-sara", "Ms. Sara", ChatPeerRole.TEACHER, "math"),
        ComplaintRecipient("t-noor", "Ms. Noor", ChatPeerRole.TEACHER, "english"),
        ComplaintRecipient("co-lina", "Ms. Lina", ChatPeerRole.COORDINATOR, "math"),
        ComplaintRecipient("co-omar", "Mr. Omar", ChatPeerRole.COORDINATOR, "english, science"),
        ComplaintRecipient("mg-nour", "Ms. Nour", ChatPeerRole.MANAGERIAL),
    )

    fun list(childId: String, status: String?): ComplaintList {
        val all = entries(childId).map { it.view() }.sortedByDescending { it.lastMessage?.createdAt ?: it.createdAt }
        val rows = when (status) {
            "open" -> all.filter { it.status == ChatThreadStatus.OPEN }
            "resolved" -> all.filter { it.status == ChatThreadStatus.RESOLVED }
            else -> all
        }
        return ComplaintList(rows, open = all.count { it.status == ChatThreadStatus.OPEN }, resolved = all.count { it.status == ChatThreadStatus.RESOLVED })
    }

    suspend fun create(childId: String, request: CreateComplaintRequest): ComplaintDetail {
        val to = recipients().firstOrNull { it.staffId == request.staffId } ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such recipient."))
        val id = "cp-${Ids.random()}"
        val at = now()
        val first = ChatMessage("m-${Ids.random()}", id, ChatSender.PARENT, parentId(), request.body.trim(), at, attachments = attached(request.attachmentIds))
        val complaint = Complaint(
            id = id, childId = childId, childName = "Maya", title = request.title.trim(), status = ChatThreadStatus.OPEN,
            recipientId = to.staffId, recipientName = to.name, recipientRole = to.peerRole, createdAt = at,
            className = "1A British", subject = to.subject, canReply = true,
        )
        val entry = Entry(complaint, mutableListOf(first), mutableListOf())
        entries(childId).add(0, entry)
        return entry.detail()
    }

    fun detail(childId: String, complaintId: String, since: String?): ComplaintDetail {
        val entry = find(childId, complaintId)
        val messages = since?.let { id -> entry.messages.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { entry.messages.drop(it + 1) } } ?: entry.messages
        return ComplaintDetail(entry.view(), messages.toList(), entry.events.toList())
    }

    suspend fun reply(childId: String, complaintId: String, request: SendChatMessageRequest): ChatMessage {
        val entry = find(childId, complaintId)
        return ChatMessage("m-${Ids.random()}", complaintId, ChatSender.PARENT, parentId(), request.body.trim(), now(), attachments = attached(request.attachmentIds))
            .also { entry.messages += it }
    }

    fun markRead(childId: String, complaintId: String): ChatReadReceipt {
        val entry = find(childId, complaintId)
        val at = now()
        entry.messages.indices.forEach { i ->
            val m = entry.messages[i]
            if (m.sender != ChatSender.PARENT && m.readAt == null) entry.messages[i] = m.copy(readAt = at)
        }
        return ChatReadReceipt(complaintId, ChatSender.PARENT, at)
    }

    /** `PATCH …/status {"status":"open"}`: the parent's reopen. Already open changes nothing and records nothing. */
    suspend fun reopen(childId: String, complaintId: String): Complaint {
        val list = entries(childId)
        val index = list.indexOfFirst { it.complaint.id == complaintId }.takeIf { it >= 0 } ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such complaint."))
        val entry = list[index]
        if (entry.complaint.status == ChatThreadStatus.OPEN) return entry.view()
        val reopened = entry.copy(complaint = entry.complaint.copy(status = ChatThreadStatus.OPEN, resolvedAt = null, resolvedByName = null))
        reopened.events += ComplaintEvent(ChatThreadStatus.OPEN, ComplaintActor.PARENT, parentId(), "You", now())
        list[index] = reopened
        return reopened.view()
    }

    private fun find(childId: String, complaintId: String): Entry =
        entries(childId).firstOrNull { it.complaint.id == complaintId } ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such complaint."))

    private fun Entry.view(): Complaint = complaint.copy(
        lastMessage = messages.lastOrNull(),
        unread = messages.count { it.sender != ChatSender.PARENT && it.readAt == null },
    )

    private fun Entry.detail() = ComplaintDetail(view(), messages.toList(), events.toList())

    private fun entries(childId: String): MutableList<Entry> = byChild.getOrPut(childId) { seed(childId) }

    private fun seed(childId: String): MutableList<Entry> {
        val day = 86_400_000L
        val base = 1_759_400_000_000L
        fun msg(thread: String, id: String, staff: String?, body: String, at: Long, read: Boolean = true) = ChatMessage(
            id, thread, if (staff == null) ChatSender.PARENT else ChatSender.TEACHER, staff ?: "fake-parent", body, at, readAt = if (read) at + 60_000 else null,
        )
        val homework = Complaint(
            id = "cp-homework", childId = childId, childName = "Maya", title = "Homework is too long every night",
            status = ChatThreadStatus.RESOLVED, recipientId = "co-lina", recipientName = "Ms. Lina", recipientRole = ChatPeerRole.COORDINATOR,
            createdAt = base - 3 * day, className = "1A British", subject = "math", resolvedAt = base - 2 * day, resolvedByName = "Ms. Lina", canReply = true,
        )
        val bus = Complaint(
            id = "cp-bus", childId = childId, childName = "Maya", title = "The school bus arrives late",
            status = ChatThreadStatus.OPEN, recipientId = "mg-nour", recipientName = "Ms. Nour", recipientRole = ChatPeerRole.MANAGERIAL,
            createdAt = base - day, className = "1A British", canReply = true,
        )
        return mutableListOf(
            Entry(
                bus,
                mutableListOf(
                    msg("cp-bus", "m-bus-1", null, "The bus has reached our stop after 7:40 three times this week.", base - day),
                    msg("cp-bus", "m-bus-2", "mg-nour", "Thank you for telling us. I am checking the route with the transport team today.", base - day + 3_600_000, read = false),
                ),
                mutableListOf(),
            ),
            Entry(
                homework,
                mutableListOf(
                    msg("cp-homework", "m-hw-1", null, "Maya spends more than an hour on math homework every night.", base - 3 * day),
                    msg("cp-homework", "m-hw-2", "co-lina", "We have shortened the worksheets for Grade 1 from this week. Please tell me how it goes.", base - 2 * day - 3_600_000),
                ),
                mutableListOf(ComplaintEvent(ChatThreadStatus.RESOLVED, ComplaintActor.STAFF, "co-lina", "Ms. Lina", base - 2 * day)),
            ),
        )
    }
}
