package quest.feature.staff

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.staff.StaffMe
import quest.api.staff.StaffPreferences
import quest.api.staff.AttendanceRequestList
import quest.api.staff.AttendanceRequestView
import quest.api.staff.NewAttendanceRequest
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffRole
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feature 13 (fix): the staff badges are **live**.
 *
 * The bottom bar's dots and the home's "unread messages" card are the same numbers, and they used to move only when
 * a tab was opened or the app came back to the front — so a message that arrived while she was looking at the home
 * left the card saying "No unread messages". The counts now follow her own `/ws/chat` frames, as the parent's do.
 */
class StaffBadgesTest {

    private val me = StaffProfile("u-1", "Noor", StaffRole.TEACHER, "TEACHER", null, "s-1", "LIS", emptyMap())

    private class FakeGateway(var unread: Int) : StaffGateway {
        var threadCalls = 0
        override suspend fun staffMe(): StaffMe = error("not used")
        override suspend fun staffPreferences(request: StaffPreferences): StaffMe = error("not used")
        override suspend fun staffThreads(): List<ChatThread> {
            threadCalls++
            return listOf(ChatThread(id = "t-1", childId = "", childName = "", teacherId = "u-2", teacherName = "Ms. Hala", unread = unread))
        }
        override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage = error("not used")
        override suspend fun staffRead(threadId: String): ChatReadReceipt = error("not used")
        override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("not used")
        override suspend fun uploadRequestAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("not used")
        override suspend fun createAttendanceRequest(request: NewAttendanceRequest): AttendanceRequestView = error("not used")
        override suspend fun myAttendanceRequests(): AttendanceRequestList = AttendanceRequestList(emptyList())
        override suspend fun attendanceRequestsToDecide(status: String?): AttendanceRequestList = AttendanceRequestList(emptyList())
        override suspend fun approveAttendanceRequest(id: String): AttendanceRequestView = error("not used")
        override suspend fun rejectAttendanceRequest(id: String): AttendanceRequestView = error("not used")
        override suspend fun notifications(limit: Int): List<NotificationView> = emptyList()
        override suspend fun unreadNotificationCount(): UnreadCount = UnreadCount(0)
        override suspend fun markNotificationRead(id: String): NotificationView = error("not used")
        override suspend fun markAllNotificationsRead(): UnreadCount = UnreadCount(0)
        override suspend fun registerDevice(request: RegisterDeviceRequest) = Unit
        override suspend fun unregisterDevice(token: String) = Unit
        // Feature 15 rounds out the port; this fake answers none of it.
        override suspend fun checkIn(request: quest.api.staff.CheckInRequest): quest.api.staff.CheckInResult = error("not used")
        override suspend fun checkOut(request: quest.api.staff.CheckInRequest): quest.api.staff.CheckInResult = error("not used")
        override suspend fun checkInToday(): quest.api.staff.CheckInToday = error("not used")
        override suspend fun checkInMonth(month: String?): quest.api.staff.CheckInMonth = error("not used")
    }

    private fun message(senderId: String) = ChatFrame.Message(
        ChatMessage("m-1", "t-1", ChatSender.TEACHER, senderId, "hello", createdAt = 1L),
    )

    @Test fun `a message from somebody else moves the counts without reopening a tab`() = runTest {
        val gateway = FakeGateway(unread = 2)
        val frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 8)
        val badges = StaffBadges(gateway) { me }
        val job = badges.start(this, frames)
        testScheduler.advanceUntilIdle()
        try {
            frames.emit(message(senderId = "u-2"))
            testScheduler.advanceUntilIdle()
            assertEquals(2, badges.counts.value.messages)
        } finally {
            job.cancel()
        }
    }

    /** Her own send comes back as a frame too, and there is nothing new for her to read in it. */
    @Test fun `her own message asks for nothing`() = runTest {
        val gateway = FakeGateway(unread = 2)
        val frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 8)
        val badges = StaffBadges(gateway) { me }
        val job = badges.start(this, frames)
        testScheduler.advanceUntilIdle()
        try {
            frames.emit(message(senderId = "u-1"))
            testScheduler.advanceUntilIdle()
            assertEquals(0, gateway.threadCalls)
            assertEquals(0, badges.counts.value.messages)
        } finally {
            job.cancel()
        }
    }

    /** The Messages tab has the rows in its hand: the bar takes the count from them rather than asking again. */
    @Test fun `rows a screen already read are counted without a request`() = runTest {
        val gateway = FakeGateway(unread = 3)
        val badges = StaffBadges(gateway) { me }
        badges.observed(listOf(ChatThread(id = "t-1", childId = "", childName = "", teacherId = "u-2", teacherName = "Ms. Hala", unread = 3)))
        assertEquals(3, badges.counts.value.messages)
        assertEquals(0, gateway.threadCalls)
    }
}
