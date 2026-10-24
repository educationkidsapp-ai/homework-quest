package quest.feature.staff.data

import kotlinx.coroutines.delay
import quest.api.ApiException
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.BroadcastAudience
import quest.api.dto.BroadcastView
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.CreateBroadcastRequest
import quest.api.dto.CreateVoiceDraftRequest
import quest.api.dto.Curriculum
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.dto.VoiceDraft
import quest.api.dto.VoiceDraftAudience
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import quest.api.dashboard.CoordinatorClass
import quest.api.dashboard.ManagementGradeGroup
import quest.api.dto.Subject
import quest.api.dashboard.WeekLessonStatus
import quest.api.staff.AttendanceRequestList
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.api.staff.AttendanceRequestStatus
import quest.api.staff.AttendanceRequestType
import quest.api.staff.LeaveChain
import quest.api.staff.LeavePreview
import quest.api.staff.LeaveType
import quest.api.staff.LeaveTypeBalance
import quest.api.staff.MyLeaveBalance
import quest.api.staff.RequestStage
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CheckInDay
import quest.api.staff.CheckInMode
import quest.api.staff.CheckInMonth
import quest.api.staff.CheckInOutcome
import quest.api.staff.CheckInRefusal
import quest.api.staff.CheckInRequest
import quest.api.staff.CheckInResult
import quest.api.staff.CheckInRules
import quest.api.staff.CheckInToday
import quest.api.staff.NewAttendanceRequest
import quest.api.staff.StaffMe
import quest.api.staff.StaffPreferences
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.VoiceAnnouncementGateway

/**
 * The in-app stand-in for `/staff`, so a build with no server still signs a teacher in and draws her tabs — the same
 * parity `FakeContentApi` gives the parent side.
 *
 * It answers one teacher with two conversations, one of them a parent's, and keeps whatever is sent into them, so the
 * Messages tab, its unread badges and the read receipts all behave.
 */
class FakeStaffApi(private val delayMillis: Long = 250) : StaffGateway, VoiceAnnouncementGateway {

    /** What `FakeAuth`'s staff half is: whichever login signed in, as a teacher of the default school. */
    var login: String = "teacher@school.test"
    var role: String = "TEACHER"
    var flagsOn: Boolean = true

    private val messages = mutableMapOf<String, MutableList<ChatMessage>>()
    private val unread = mutableMapOf<String, Int>()
    private val devices = mutableMapOf<String, RegisterDeviceRequest>()
    private val readRows = mutableSetOf<String>()

    /** Feature 19: her own choice, kept in memory so the Account switch behaves without a server. */
    private var afterHoursAlerts: Boolean = false

    override suspend fun staffMe(): StaffMe {
        net()
        return StaffMe(
            userId = ME, displayName = "Ms Noor", role = role, phone = "+966500000000",
            schoolId = "school-fake", schoolName = "Learning International School",
            // Features 15 and 19 are on in the fake school, so a build with no server still draws the check-in card
            // and the after-hours switch.
            // Features 15, 19 and 22 are on in the fake school, so an ADMIN or MANAGERIAL login also draws the voice
            // announcement card; the role itself still decides, as on the server.
            flags = DEFAULT_FLAGS + mapOf(
                "chat" to true, "staffMobileApp" to flagsOn, "staffAttendance" to flagsOn, "chatOfficeHours" to flagsOn,
                "announcements" to flagsOn, "voiceAnnouncement" to flagsOn,
            ),
            afterHoursAlerts = afterHoursAlerts,
        )
    }

    override suspend fun staffPreferences(request: StaffPreferences): StaffMe {
        net()
        afterHoursAlerts = request.afterHoursAlerts
        return staffMe()
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

    override suspend fun uploadRequestAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef =
        uploadAttachment(file, onProgress)

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

    // ---------------------------------------------------------------- feature 18: remote days and corrections

    /** Her requests, newest first, kept in memory: the fake decider is the manager of the fake staff thread. */
    private val requests = mutableListOf<AttendanceRequestView>()

    override suspend fun createAttendanceRequest(request: NewAttendanceRequest): AttendanceRequestView {
        net()
        val view = AttendanceRequestView(
            id = Ids.random(), type = request.type, status = AttendanceRequestStatus.PENDING,
            staff = "USER:$ME", staffName = "Ms Noor", date = request.date, reason = request.reason,
            correctionKind = request.correctionKind, checkIn = request.checkIn, checkOut = request.checkOut,
            attachment = request.attachmentId?.let { AttachmentRef(id = it, type = "image/jpeg", name = "proof.jpg", sizeBytes = 0) },
            createdAt = Today.epochMillis(),
            // Feature 34: a leave carries its type, its range and the days it uses; a permission its two times.
            leaveType = request.leaveType,
            toDate = request.toDate,
            fromTime = request.fromTime,
            toTime = request.toTime,
            days = request.toDate?.let { to -> workingDays(request.date, to).size },
            minutes = request.fromTime?.let { from ->
                request.toTime?.let { to -> minutesOfDay(to) - minutesOfDay(from) }
            },
            stage = RequestStage.MANAGER,
        )
        requests.add(0, view)
        return view
    }

    override suspend fun myAttendanceRequests(): AttendanceRequestList { net(); return AttendanceRequestList(requests.toList()) }

    override suspend fun attendanceRequestsToDecide(status: String?): AttendanceRequestList {
        net()
        return AttendanceRequestList(requests.filter { status == null || it.status == status })
    }

    override suspend fun approveAttendanceRequest(id: String): AttendanceRequestView = decide(id, AttendanceRequestStatus.APPROVED)

    override suspend fun rejectAttendanceRequest(id: String): AttendanceRequestView = rejectAttendanceRequest(id, null)

    /**
     * Feature 34: a leave or a permission may not be rejected without a reason — 400, exactly as the server answers
     * the Android shade's bare Reject, so the app's own fallback can be exercised with no server at all.
     */
    override suspend fun rejectAttendanceRequest(id: String, reason: String?): AttendanceRequestView {
        val row = requests.firstOrNull { it.id == id }
        val leaveKind = row?.type == AttendanceRequestType.LEAVE || row?.type == AttendanceRequestType.PERMISSION
        if (leaveKind && reason.isNullOrBlank()) throw ApiException(ApiError(ApiError.BAD_REQUEST, "A rejection needs a reason."))
        return decide(id, AttendanceRequestStatus.REJECTED, reason)
    }

    /** Feature 34: the author withdraws her own request while nobody has decided it. */
    override suspend fun cancelAttendanceRequest(id: String): AttendanceRequestView =
        decide(id, AttendanceRequestStatus.CANCELLED, decider = null)

    override suspend fun myLeaveBalance(): MyLeaveBalance {
        net()
        val used = requests.filter { it.status == AttendanceRequestStatus.APPROVED }
            .groupBy { it.leaveType }
            .mapValues { (_, rows) -> rows.sumOf { it.days ?: 0 } }
        return MyLeaveBalance(
            year = Today.date().year,
            category = "TEACHER",
            chain = LeaveChain.MANAGER,
            permissionMaxMinutes = 180,
            types = LeaveType.ALL.map { type ->
                val allowance = ALLOWANCES[type]
                LeaveTypeBalance(
                    type = type,
                    enabled = true,
                    documentAfterDays = if (type == LeaveType.SICK) 2 else null,
                    allowanceDays = allowance,
                    usedDays = used[type] ?: 0,
                    remainingDays = allowance?.let { it - (used[type] ?: 0) },
                )
            },
        )
    }

    override suspend fun leavePreview(leaveType: String, from: String, to: String): LeavePreview {
        net()
        val dates = workingDays(from, to)
        val balance = myLeaveBalance().types.first { it.type == leaveType }
        return LeavePreview(
            leaveType = leaveType, from = from, to = to, days = dates.size, dates = dates,
            allowanceDays = balance.allowanceDays, usedDays = balance.usedDays, remainingDays = balance.remainingDays,
            remainingAfter = balance.remainingDays?.let { it - dates.size },
            documentRequired = balance.documentAfterDays?.let { dates.size > it } == true,
        )
    }

    /** Friday and Saturday are the pilot school's weekend, so the fake's "school days" skip them. */
    private fun workingDays(from: String, to: String): List<String> {
        val first = runCatching { LocalDate.parse(from) }.getOrNull() ?: return emptyList()
        val last = runCatching { LocalDate.parse(to) }.getOrNull() ?: return emptyList()
        if (last < first) return emptyList()
        val days = mutableListOf<String>()
        var day = first
        while (day <= last) {
            if (day.dayOfWeek != DayOfWeek.FRIDAY && day.dayOfWeek != DayOfWeek.SATURDAY) days += day.toString()
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return days
    }

    private fun minutesOfDay(clock: String): Int {
        val parts = clock.split(":")
        return ((parts.getOrNull(0)?.toIntOrNull() ?: 0) * 60) + (parts.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    private suspend fun decide(
        id: String,
        status: String,
        reason: String? = null,
        decider: String? = "Mr Adel",
    ): AttendanceRequestView {
        net()
        val index = requests.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException(ApiError(ApiError.NOT_FOUND, "request"))
        if (requests[index].status != AttendanceRequestStatus.PENDING) throw ApiException(ApiError("conflict", "already decided"))
        val decided = requests[index].copy(
            status = status,
            decidedBy = decider,
            decidedAt = Today.epochMillis(),
            stage = null,
            rejectReason = reason,
        )
        requests[index] = decided
        return decided
    }



    // ---------------------------------------------------------------- feature 22: voice note to announcement

    /** Every draft this fake prepared, and the broadcasts Send actually posted — a build with no server still shows
     * that nothing goes out until she presses it. */
    val drafts = mutableListOf<VoiceDraft>()
    val posted = mutableListOf<CreateBroadcastRequest>()

    override suspend fun managementClasses(): List<ManagementGradeGroup> {
        net()
        return listOf(Curriculum.BRITISH, Curriculum.AMERICAN).flatMap { track ->
            (1..3).map { grade ->
                ManagementGradeGroup(
                    curriculum = track, grade = grade, sections = 1, children = 22,
                    classes = listOf(
                        CoordinatorClass(
                            classId = "sec-${track.name.lowercase()}-$grade", className = "$grade" + "A",
                            curriculum = track, grade = grade, subject = Subject.MATH,
                            teacherId = ME, teacherName = "Ms Noor", childrenCount = 22,
                            todayStatus = WeekLessonStatus.NONE,
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun voiceDraft(request: CreateVoiceDraftRequest): VoiceDraft {
        net()
        if (request.transcript.isBlank()) throw ApiException(ApiError(ApiError.BAD_REQUEST, "transcript"))
        val draft = VoiceDraft(
            id = Ids.random(),
            title = "رحلة الصف الثالث",
            bodyAr = "نحيط أولياء أمور طلاب الصف الثالث علمًا بأن الرحلة المدرسية غدًا، ونرجو تزويد الطالب بوجبة خفيفة وماء.",
            bodyEn = "Dear parents of Grade 3, the school trip is tomorrow. Please send a light snack and water with your child.",
            audience = VoiceDraftAudience(curriculum = Curriculum.BRITISH, grade = 3),
            expiresAt = Today.epochMillis() + 2 * 24 * 3_600_000L,
            transcript = request.transcript,
        )
        drafts += draft
        return draft
    }

    override suspend fun createBroadcast(request: CreateBroadcastRequest): BroadcastView {
        net()
        posted += request
        return BroadcastView(
            id = Ids.random(), kind = request.kind, authorId = ME, authorName = "Ms Noor",
            authorRole = ChatStaffRole.MANAGERIAL, title = request.title, bodyEn = request.bodyEn.orEmpty(),
            bodyAr = request.bodyAr, grade = request.grade, sectionIds = request.sectionIds,
            audience = request.audience.ifEmpty { listOf(BroadcastAudience.PARENTS) },
            expiresAt = request.expiresAt, createdAt = Today.epochMillis(),
        )
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
        /** Feature 34: what the fake school gives a teacher a year; unpaid and other have no balance, as the spec says. */
        val ALLOWANCES = mapOf(LeaveType.ANNUAL to 21, LeaveType.SICK to 10, LeaveType.EMERGENCY to 5)

        const val ME = "staff-me"
        const val PARENT_THREAD = "fake-thread-parent"
        const val STAFF_THREAD = "fake-thread-staff"
    }
}
