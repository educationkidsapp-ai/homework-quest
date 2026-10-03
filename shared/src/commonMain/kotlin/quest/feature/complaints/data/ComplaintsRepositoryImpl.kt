package quest.feature.complaints.data

import quest.api.ContentApi
import quest.api.dto.ChatMessage
import quest.api.dto.Complaint
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintList
import quest.api.dto.ComplaintRecipient
import quest.api.dto.CreateComplaintRequest
import quest.api.dto.SendChatMessageRequest
import quest.feature.complaints.domain.ComplaintsRepository

/** M8: B6's parent routes, `/children/{id}/complaints…`, straight through the contract's [ContentApi]. */
class ComplaintsRepositoryImpl(private val api: ContentApi) : ComplaintsRepository {
    override suspend fun list(childId: String, status: String?): ComplaintList = api.complaints(childId, status)

    override suspend fun recipients(childId: String): List<ComplaintRecipient> = api.complaintRecipients(childId)

    override suspend fun create(childId: String, staffId: String, title: String, body: String, clientId: String, attachmentIds: List<String>): ComplaintDetail =
        api.createComplaint(childId, CreateComplaintRequest(staffId = staffId, title = title.trim(), body = body.trim(), clientId = clientId, attachmentIds = attachmentIds))

    override suspend fun detail(childId: String, complaintId: String, since: String?): ComplaintDetail =
        api.complaint(childId, complaintId, since = since)

    override suspend fun reply(childId: String, complaintId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage =
        api.sendComplaintMessage(childId, complaintId, SendChatMessageRequest(body = body.trim(), clientId = clientId, attachmentIds = attachmentIds.ifEmpty { null }))

    override suspend fun markRead(childId: String, complaintId: String) {
        api.markComplaintRead(childId, complaintId)
    }

    override suspend fun reopen(childId: String, complaintId: String): Complaint = api.reopenComplaint(childId, complaintId)
}
