package quest.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.UploadFile
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.staff.AttendanceRequestList
import quest.api.staff.AttendanceRequestStatus
import quest.api.staff.AttendanceRequestType
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CheckInMonth
import quest.api.staff.CheckInRequest
import quest.api.staff.CheckInResult
import quest.api.staff.CheckInToday
import quest.api.staff.CorrectionKind
import quest.api.staff.NewAttendanceRequest
import quest.api.staff.StaffMe
import quest.api.staff.StaffPreferences
import quest.core.mvi.MviState
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.StagedUpload
import quest.feature.chat.domain.UploadStaging
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.presentation.AttendanceRequestsContract
import quest.feature.staff.presentation.AttendanceRequestsViewModel
import quest.feature.staff.presentation.RequestMessage
import quest.feature.staff.presentation.StaffStrings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 18 on the phone: the two requests an employee makes herself.
 *
 * The tests that matter are the refusals, because nothing the app sends may be a surprise to her: a reason is
 * required, a remote day is never asked for a day that has passed, a correction is never asked for a day that has
 * not come, and a correction whose kind needs a time does not go without one. The file, when there is one, is
 * uploaded **before** the request, so no request ever names an attachment the server does not have.
 */
class AttendanceRequestsViewModelTest {

    private val today = LocalDate.parse("2026-10-14")

    /** Only feature 18's routes; the rest of the port is somebody else's test. */
    private class FakeGateway(
        var failure: Throwable? = null,
        var uploadFailure: Throwable? = null,
        var requests: List<AttendanceRequestView> = emptyList(),
    ) : StaffGateway {
        val created = mutableListOf<NewAttendanceRequest>()
        val uploads = mutableListOf<StagedUpload>()
        var listCalls = 0

        override suspend fun createAttendanceRequest(request: NewAttendanceRequest): AttendanceRequestView {
            failure?.let { throw it }
            created += request
            val view = AttendanceRequestView(
                id = "r-${created.size}", type = request.type, status = AttendanceRequestStatus.PENDING,
                staff = "USER:u-1", staffName = "Ms Noor", date = request.date, reason = request.reason,
                correctionKind = request.correctionKind, checkIn = request.checkIn, checkOut = request.checkOut,
                createdAt = 0L,
            )
            requests = listOf(view) + requests
            return view
        }

        override suspend fun myAttendanceRequests(): AttendanceRequestList {
            listCalls++
            failure?.let { throw it }
            return AttendanceRequestList(requests)
        }

        override suspend fun uploadRequestAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef {
            uploadFailure?.let { throw it }
            uploads += file
            onProgress(1f)
            return AttachmentRef(id = "att-${uploads.size}", type = file.contentType, name = file.name, sizeBytes = file.size)
        }

        override suspend fun attendanceRequestsToDecide(status: String?): AttendanceRequestList = AttendanceRequestList(emptyList())
        override suspend fun approveAttendanceRequest(id: String): AttendanceRequestView = error("not used")
        override suspend fun rejectAttendanceRequest(id: String): AttendanceRequestView = error("not used")
        override suspend fun checkIn(request: CheckInRequest): CheckInResult = error("not used")
        override suspend fun checkOut(request: CheckInRequest): CheckInResult = error("not used")
        override suspend fun checkInToday(): CheckInToday = error("not used")
        override suspend fun checkInMonth(month: String?): CheckInMonth = CheckInMonth("2026-10", emptyList())
        override suspend fun staffMe(): StaffMe = error("not used")
        override suspend fun staffPreferences(request: StaffPreferences): StaffMe = error("not used")
        override suspend fun staffThreads(): List<ChatThread> = emptyList()
        override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage = error("not used")
        override suspend fun staffRead(threadId: String): ChatReadReceipt = error("not used")
        override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("not used")
        override suspend fun notifications(limit: Int): List<NotificationView> = emptyList()
        override suspend fun unreadNotificationCount(): UnreadCount = UnreadCount(0)
        override suspend fun markNotificationRead(id: String): NotificationView = error("not used")
        override suspend fun markAllNotificationsRead(): UnreadCount = UnreadCount(0)
        override suspend fun registerDevice(request: RegisterDeviceRequest) = Unit
        override suspend fun unregisterDevice(token: String) = Unit
    }

    private class MemoryStaging : UploadStaging {
        val live = mutableMapOf<String, ByteArray>()
        var count = 0
        override suspend fun stage(file: UploadFile): StagedUpload {
            val path = "staged-${++count}"
            live[path] = file.bytes
            return StagedUpload(path, file.fileName, file.mimeType, file.bytes.size.toLong())
        }
        override fun discard(staged: StagedUpload) { live.remove(staged.path) }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(gateway: FakeGateway, staging: UploadStaging = MemoryStaging()) =
        AttendanceRequestsViewModel(gateway, staging, today = { today }).also { built += it }

    private suspend fun <S : MviState> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    /** A PDF the picker handed over, read as it will be uploaded. */
    private fun pdf(name: String = "proof.pdf") =
        PickedFile(name, size = 2_048, photo = false) { UploadFile(name, "application/pdf", ByteArray(2_048) { 1 }) }

    // ---- 1. a remote day

    @Test fun `a remote day defaults to tomorrow and goes with its reason`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        assertEquals("2026-10-15", vm.state.value.form?.date, "tomorrow, as the spec words the action")

        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("A rain day; I will teach from home"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        val sent = gateway.created.single()
        assertEquals(AttendanceRequestType.REMOTE_DAY, sent.type)
        assertEquals("2026-10-15", sent.date)
        assertEquals("A rain day; I will teach from home", sent.reason)
        assertNull(sent.correctionKind, "a remote day names no correction kind")
        assertNull(sent.checkIn)
        assertNull(sent.attachmentId)
        assertEquals(RequestMessage.Sent, vm.state.value.message)
        assertNull(vm.state.value.form, "the sheet closes on its own")
        assertEquals("أُرسل طلبك إلى مديرك.", StaffStrings.ar.requestMessage(RequestMessage.Sent))
        assertEquals("Your request was sent to your manager.", StaffStrings.en.requestMessage(RequestMessage.Sent))
        // The list is re-read, so "My requests" shows the new row without the screen asking again.
        settle(vm.state) { it.requests.isNotEmpty() }
        assertEquals(listOf("r-1"), vm.state.value.requests.map { it.id })
    }

    @Test fun `a remote day for a day that has passed is refused before anything is sent`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetDate("2026-10-13"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.DateNotPast, vm.state.value.message)
        assertTrue(gateway.created.isEmpty())
        assertEquals("اختر اليوم أو يومًا بعده.", StaffStrings.ar.requestMessage(RequestMessage.DateNotPast))
    }

    @Test fun `a request with no reason is refused`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.ReasonNeeded, vm.state.value.message)
        assertTrue(gateway.created.isEmpty())
    }

    // ---- 2. a correction

    @Test fun `a forgotten check-in is corrected with the claimed time`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-13"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckIn("06:55"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("I was at the gate; the app would not open"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message == RequestMessage.Sent }

        val sent = gateway.created.single()
        assertEquals(AttendanceRequestType.CORRECTION, sent.type)
        assertEquals(CorrectionKind.FORGOT_CHECK_IN, sent.correctionKind)
        assertEquals("2026-10-13", sent.date)
        assertEquals("06:55", sent.checkIn)
        assertNull(sent.checkOut)
        assertNull(sent.attachmentId)
    }

    @Test fun `a correction with a PDF uploads the file first and names it on the request`() = runBlocking {
        val gateway = FakeGateway()
        val staging = MemoryStaging()
        val vm = viewModel(gateway, staging)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-14"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetCorrectionKind(CorrectionKind.PHONE_PROBLEM))
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckIn("07:02"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("The phone had no battery"))
        vm.dispatch(AttendanceRequestsContract.Intent.AddFiles(listOf(pdf())))
        settle(vm.state) { it.drafts.singleOrNull()?.ref != null }
        assertEquals("proof.pdf", gateway.uploads.single().name)

        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message == RequestMessage.Sent }

        assertEquals("att-1", gateway.created.single().attachmentId)
        assertTrue(staging.live.isEmpty(), "the staged copy goes as soon as the server has it")
        assertTrue(vm.state.value.drafts.isEmpty())
    }

    @Test fun `Send waits while the file is still going up`() = runBlocking {
        val gateway = FakeGateway(uploadFailure = ApiException(ApiError(ApiError.NETWORK, "no route")))
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-14"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckIn("07:02"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.AddFiles(listOf(pdf())))
        settle(vm.state) { it.drafts.singleOrNull()?.failed == true }

        assertTrue(!vm.state.value.canSubmit, "a failed upload keeps Send shut until it is retried or removed")
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        delay(50)
        assertTrue(gateway.created.isEmpty())
    }

    @Test fun `a correction for a day that has not come is refused`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-20"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckIn("07:00"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.DateNotFuture, vm.state.value.message)
        assertTrue(gateway.created.isEmpty())
    }

    @Test fun `each correction kind asks for the time it is about`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)

        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-13"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        // Forgot the check-in, but only a check-out was written.
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckOut("14:00"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }
        assertEquals(RequestMessage.TimeNeeded, vm.state.value.message)

        // Forgot the check-out: the check-out is the one it needs, and the same form now goes.
        vm.dispatch(AttendanceRequestsContract.Intent.SetCorrectionKind(CorrectionKind.FORGOT_CHECK_OUT))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message == RequestMessage.Sent }
        assertEquals("14:00", gateway.created.single().checkOut)
        assertEquals("اكتب الوقت بالصيغة HH:mm.", StaffStrings.ar.requestMessage(RequestMessage.TimeNeeded))
    }

    @Test fun `a time that is not HH mm is refused`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenCorrection("2026-10-13"))
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetCheckIn("7am"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.TimeNeeded, vm.state.value.message)
        assertTrue(gateway.created.isEmpty())
    }

    @Test fun `a date that is not a date is refused`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.SetDate("tomorrow"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.DateInvalid, vm.state.value.message)
        assertTrue(gateway.created.isEmpty())
    }

    // ---- 3. the network, and the list

    @Test fun `no internet keeps the form open and says so`() = runBlocking {
        val gateway = FakeGateway(failure = ApiException(ApiError(ApiError.NETWORK, "no route to host")))
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.Offline, vm.state.value.message)
        assertNotNull(vm.state.value.form, "her typing is not thrown away")
        assertEquals("تحتاج اتصالًا بالإنترنت لإرسال الطلب.", StaffStrings.ar.requestMessage(RequestMessage.Offline))
    }

    @Test fun `a refusal from the server is its own message`() = runBlocking {
        val gateway = FakeGateway(failure = ApiException(ApiError("conflict", "already a request for that day")))
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.OpenRemoteDay)
        settle(vm.state) { it.form != null }
        vm.dispatch(AttendanceRequestsContract.Intent.SetReason("why"))
        vm.dispatch(AttendanceRequestsContract.Intent.Submit)
        settle(vm.state) { it.message != null }

        assertEquals(RequestMessage.Failed, vm.state.value.message)
    }

    @Test fun `my requests are the rows the server answered with, and a decided one names its decider`() = runBlocking {
        val decided = AttendanceRequestView(
            id = "r-9", type = AttendanceRequestType.CORRECTION, status = AttendanceRequestStatus.APPROVED,
            staff = "USER:u-1", staffName = "Ms Noor", date = "2026-10-12", reason = "forgot",
            correctionKind = CorrectionKind.FORGOT_CHECK_OUT, checkOut = "14:05", createdAt = 1L,
            decidedBy = "Mr Adel", decidedAt = 2L, originalCheckOut = null,
        )
        val gateway = FakeGateway(requests = listOf(decided))
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.Load)
        settle(vm.state) { !it.loading }

        assertEquals(listOf("r-9"), vm.state.value.requests.map { it.id })
        assertEquals("تمت الموافقة", StaffStrings.ar.requestStatusName(AttendanceRequestStatus.APPROVED))
        assertEquals("Correction", StaffStrings.en.requestTypeName(AttendanceRequestType.CORRECTION))
        assertEquals("نسيت تسجيل الانصراف", StaffStrings.ar.correctionKindName(CorrectionKind.FORGOT_CHECK_OUT))
        assertEquals("Decided by Mr Adel", StaffStrings.en.requestDecidedBy.replace("{name}", "Mr Adel"))
    }

    @Test fun `a list that cannot be read says so instead of showing an empty record`() = runBlocking {
        val gateway = FakeGateway(failure = ApiException(ApiError(ApiError.NETWORK, "offline")))
        val vm = viewModel(gateway)
        vm.dispatch(AttendanceRequestsContract.Intent.Load)
        settle(vm.state) { !it.loading }

        assertTrue(vm.state.value.failed)
        assertTrue(vm.state.value.requests.isEmpty())
    }
}
