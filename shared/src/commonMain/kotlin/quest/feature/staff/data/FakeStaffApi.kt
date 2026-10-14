package quest.feature.staff.data

import kotlinx.coroutines.delay
import quest.api.ApiException
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.staff.CheckInDay
import quest.api.staff.CheckInMode
import quest.api.staff.CheckInMonth
import quest.api.staff.CheckInOutcome
import quest.api.staff.CheckInRefusal
import quest.api.staff.CheckInRequest
import quest.api.staff.CheckInResult
import quest.api.staff.CheckInRules
import quest.api.staff.CheckInToday
import quest.api.staff.StaffMe
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.StaffGateway

/**
 * The in-app stand-in for `/staff`, so a build with no server still signs a teacher in and draws her tabs — the same
 * parity `FakeContentApi` gives the parent side.
 *
 * It answers one teacher with two conversations, one of them a parent's, and keeps whatever is sent into them, so the
 * Messages tab, its unread badges and the read receipts all behave.
 */
class FakeStaffApi(private val delayMillis: Long = 250) : StaffGateway {

    /** What `FakeAuth`'s staff half is: whichever login signed in, as a teacher of the default school. */
    var login: String = "teacher@school.test"
    var role: String = "TEACHER"
    var flagsOn: Boolean = true

    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val unread = mutableMapOf<String, Int>()
    private val devices = mutableMapOf<String, RegisterDeviceRequest>()
    private val readRows = mutableSetOf<String>()

    override suspend fun staffMe(): StaffMe {
        net()
        return StaffMe(
            userId = ME, displayName = "Ms Noor", role = role, phone = "+966500000000",
            schoolId = "school-fake", schoolName = "Learning International School",
            // Feature 15 is on in the fake school, so a build with no server still draws the check-in card.
            flags = DEFAULT_FLAGS + mapOf("chat" to true, "staffMobileApp" to flagsOn, "staffAttendance" to flagsOn),
        )
    }

    override suspend fun staffThreads(): List<ChatThread> {
        net()
        if (role == "WORKER") return emptyList()
        return listOf(parentThread(), staffThread()).sortedByDescending { it.lastMessage?.createdAt ?: 0L }
    }

    override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> {
        net()
        val all = history(threadId)
        if (since == null) return all
        val i = all.indexOfFirst { it.id == since }
        return if (i < 0) emptyList() else all.drop(i + 1)
    }

    override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage {
        net()
        if (role == "WORKER") throw ApiException(ApiError(ApiError.NOT_FOUND, "thread"))
        val message = ChatMessage(Ids.random(), threadId, ChatSender.TEACHER, ME, request.body, Today.epochMillis())
        history(threadId).add(message)
        return message
    }

    override suspend fun staffRead(threadId: String): ChatReadReceipt {
        net()
        unread[threadId] = 0
        return ChatReadReceipt(threadId, ChatSender.TEACHER, Today.epochMillis())
    }

    override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef {
        net()
        onProgress(1f)
        return AttachmentRef(id = Ids.random(), type = file.contentType, name = file.name, sizeBytes = file.size)
    }

    override suspend fun notifications(limit: Int): List<NotificationView> {
        net()
        return rows().take(limit)
    }

    override suspend fun unreadNotificationCount(): UnreadCount { net(); return UnreadCount(rows().count { it.readAt == null }) }

    override suspend fun markNotificationRead(id: String): NotificationView {
        net()
        readRows += id
        return rows().first { it.id == id }
    }

    override suspend fun markAllNotificationsRead(): UnreadCount {
        net()
        readRows += rows().map { it.id }
        return UnreadCount(0)
    }

    override suspend fun registerDevice(request: RegisterDeviceRequest) { net(); devices[request.token] = request }
    override suspend fun unregisterDevice(token: String) { net(); devices.remove(token) }

    // ---------------------------------------------------------------- feature 15: check in and out

    /** Today's record, kept in memory: the fake school's geofence always contains the phone. */
    private var checkInAt: String? = null
    private var checkOutAt: String? = null
    private var lateMinutes: Int? = null

    override suspend fun checkIn(request: CheckInRequest): CheckInResult {
        net()
        refuse(request)?.let { return it }
        checkInAt?.let { return CheckInResult(CheckInOutcome.ALREADY, serverTime = it, lateMinutes = lateMinutes) }
        val now = clock()
        checkInAt = now
        // Late after 07:00, as a plausible school start — the real answer is the server's `ScheduleResolver`.
        lateMinutes = ((minutesOf(now) - 7 * 60).coerceAtLeast(0))
        return CheckInResult(CheckInOutcome.RECORDED, serverTime = now, lateMinutes = lateMinutes, distanceMeters = 35)
    }

    override suspend fun checkOut(request: CheckInRequest): CheckInResult {
        net()
        refuse(request)?.let { return it }
        if (checkInAt == null) return CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.NOT_CHECKED_IN)
        val now = clock()
        checkOutAt = now
        return CheckInResult(CheckInOutcome.RECORDED, serverTime = now, distanceMeters = 35)
    }

    /** The two checks the fake can honestly make: everything else needs the school's pin, which lives on the server. */
    private fun refuse(request: CheckInRequest): CheckInResult? = when {
        request.mocked -> CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.MOCK_LOCATION)
        request.accuracyMeters > CheckInRules.MAX_ACCURACY_METERS ->
            CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.WEAK_ACCURACY)
        else -> null
    }

    override suspend fun checkInToday(): CheckInToday {
        net()
        return CheckInToday(date = Today.date().toString(), checkIn = checkInAt, checkOut = checkOutAt, lateMinutes = lateMinutes)
    }

    override suspend fun checkInMonth(month: String?): CheckInMonth {
        net()
        val today = Today.date()
        val asked = month ?: "${today.year}-${two(today.monthNumber)}"
        val days = (1..today.dayOfMonth).map { day ->
            val date = "$asked-${two(day)}"
            when {
                day == today.dayOfMonth -> CheckInDay(
                    date = date, checkIn = checkInAt, checkOut = checkOutAt, mode = CheckInMode.ONSITE,
                    status = if (checkInAt == null) "ABSENT" else if ((lateMinutes ?: 0) > 0) "LATE" else "ON_TIME",
                    lateMinutes = lateMinutes,
                )
                // A plausible month: one late day, two days off, the rest on time.
                day % 7 == 0 -> CheckInDay(date = date, status = "DAY_OFF")
                day % 11 == 0 -> CheckInDay(date = date, checkIn = "07:12", checkOut = "14:05", status = "LATE", lateMinutes = 12, mode = CheckInMode.ONSITE)
                else -> CheckInDay(date = date, checkIn = "06:52", checkOut = "14:02", status = "ON_TIME", lateMinutes = 0, mode = CheckInMode.ONSITE)
            }
        }
        return CheckInMonth(asked, days)
    }

    private fun clock(): String {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return "${two(now.hour)}:${two(now.minute)}"
    }

    private fun minutesOf(clock: String): Int {
        val parts = clock.split(":")
        return (parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private fun two(value: Int): String = if (value < 10) "0$value" else "$value"

    // ---------------------------------------------------------------- the fake school

    private fun history(threadId: String): MutableList<ChatMessage> = messages.getOrPut(threadId) {
        when (threadId) {
            PARENT_THREAD -> mutableListOf(
                ChatMessage("fs-1", threadId, ChatSender.PARENT, "parent-1", "Good morning — will there be homework today?", Today.epochMillis() - 3_600_000L),
            )
            else -> mutableListOf(
                ChatMessage("fs-2", threadId, ChatSender.TEACHER, "mgr-1", "Please send me this week's plan.", Today.epochMillis() - 7_200_000L),
            )
        }
    }

    private fun parentThread(): ChatThread {
        val last = history(PARENT_THREAD).lastOrNull()
        return ChatThread(
            id = PARENT_THREAD, childId = "child-1", childName = "Lina Hassan", teacherId = ME, teacherName = "Ms Noor",
            className = "3A", subject = "math", unread = unread.getOrElse(PARENT_THREAD) { 1 }, lastMessage = last,
            staffRole = ChatStaffRole.TEACHER, parentName = "Hassan Ali", peerRole = ChatPeerRole.PARENT,
        )
    }

    private fun staffThread(): ChatThread {
        val last = history(STAFF_THREAD).lastOrNull()
        return ChatThread(
            id = STAFF_THREAD, childId = "", childName = "", teacherId = "mgr-1", teacherName = "Mr Adel",
            unread = unread.getOrElse(STAFF_THREAD) { 0 }, lastMessage = last,
            staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.MANAGERIAL,
        )
    }

    private fun rows(): List<NotificationView> = listOf(
        NotificationView(
            id = "sn-1", kind = NotificationKind.CHAT_MESSAGE, title = "New message from Hassan Ali",
            body = "Good morning — will there be homework today?", link = "/teacher/chat?thread=$PARENT_THREAD",
            createdAt = Today.epochMillis() - 3_600_000L, readAt = if ("sn-1" in readRows) Today.epochMillis() else null,
        ),
    )

    private suspend fun net() { if (delayMillis > 0) delay(delayMillis) }

    private companion object {
        const val ME = "staff-me"
        const val PARENT_THREAD = "fake-thread-parent"
        const val STAFF_THREAD = "fake-thread-staff"
    }
}
