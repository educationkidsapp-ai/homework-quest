package quest.feature.staff.domain

import quest.api.dto.AttachmentRef
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.UnreadCount
import quest.api.staff.StaffApi
import quest.api.staff.StaffAttendanceRequestsApi
import quest.api.staff.StaffCheckInApi
import quest.feature.chat.domain.StagedUpload

/**
 * Everything the staff side of the app asks of the server, behind one port so a desktop test can answer it and the
 * app runs with `FakeStaffApi` when there is no server (the `RemoteContentApi`/`FakeContentApi` parity rule).
 *
 * The four chat routes and `GET /staff/me` are the contract's own [StaffApi], the four check-in routes of
 * feature 15 are [StaffCheckInApi] and feature 18's remote-day and correction requests are
 * [StaffAttendanceRequestsApi]. The rest are routes that already
 * existed and now take a staff token too, so they are named here rather than invented: the bell, the device
 * registration and the chat upload.
 */
interface StaffGateway : StaffApi, StaffCheckInApi, StaffAttendanceRequestsApi {
    /** `POST /media/chat-attachments`: one photo or PDF for a staff message, streamed from the staged file. */
    suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit = {}): AttachmentRef

    /**
     * Feature 18: `POST /staff/attendance/attachments` — the photo or the PDF a correction request leans on, streamed
     * from the staged file exactly as a chat file is. Its own route because the server keeps the two apart: a
     * correction's proof is not a message's attachment.
     */
    suspend fun uploadRequestAttachment(file: StagedUpload, onProgress: (Float) -> Unit = {}): AttachmentRef

    suspend fun notifications(limit: Int = 50): List<NotificationView>
    suspend fun unreadNotificationCount(): UnreadCount
    suspend fun markNotificationRead(id: String): NotificationView
    suspend fun markAllNotificationsRead(): UnreadCount

    suspend fun registerDevice(request: RegisterDeviceRequest)
    suspend fun unregisterDevice(token: String)
}
