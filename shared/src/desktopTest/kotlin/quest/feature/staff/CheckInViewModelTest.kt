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
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.staff.CheckInDay
import quest.api.staff.CheckInMonth
import quest.api.staff.CheckInOutcome
import quest.api.staff.CheckInRefusal
import quest.api.staff.CheckInRequest
import quest.api.staff.CheckInResult
import quest.api.staff.CheckInToday
import quest.api.staff.StaffMe
import quest.core.mvi.MviState
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult
import quest.core.platform.LocationFix
import quest.core.platform.LocationReader
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.CheckInPrefs
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.presentation.CheckInContract
import quest.feature.staff.presentation.CheckInMessage
import quest.feature.staff.presentation.CheckInViewModel
import quest.feature.staff.presentation.MyRecordContract
import quest.feature.staff.presentation.MyRecordViewModel
import quest.feature.staff.presentation.StaffStrings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 15 on the phone.
 *
 * Two of these tests are the ones that matter most, and neither is about a happy path: **a cancelled biometric sends
 * nothing** (no attempt row, nothing for the employee to explain to the Admin), and **the fix is forwarded exactly as
 * the platform read it** — the mock flag included — because the app judging its own location is the one way a faked
 * check-in gets through.
 *
 * `BroadcastsViewModelTest`'s rule applies here too: every view model built runs its intent loop on
 * `Dispatchers.Main`, so each one is cancelled before Main is reset.
 */
class CheckInViewModelTest {

    /** Only feature 15's four routes; everything else on the port is somebody else's test. */
    private class FakeGateway(
        var today: CheckInToday = CheckInToday(date = "2026-10-14"),
        var answer: CheckInResult = CheckInResult(CheckInOutcome.RECORDED, serverTime = "06:52", lateMinutes = 0),
        var failure: Throwable? = null,
        var month: CheckInMonth = CheckInMonth("2026-10", emptyList()),
    ) : StaffGateway {
        val checkIns = mutableListOf<CheckInRequest>()
        val checkOuts = mutableListOf<CheckInRequest>()
        val monthsAsked = mutableListOf<String?>()

        override suspend fun checkIn(request: CheckInRequest): CheckInResult {
            failure?.let { throw it }
            checkIns += request
            return answer
        }

        override suspend fun checkOut(request: CheckInRequest): CheckInResult {
            failure?.let { throw it }
            checkOuts += request
            return answer
        }

        override suspend fun checkInToday(): CheckInToday = failure?.let { throw it } ?: today

        override suspend fun checkInMonth(month: String?): CheckInMonth {
            monthsAsked += month
            return failure?.let { throw it } ?: this.month
        }

        override suspend fun staffMe(): StaffMe = error("not used")
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

    private class FakeLocation(
        var permission: Boolean = true,
        var fix: LocationFix? = LocationFix(24.7, 46.6, accuracyMeters = 11.0),
    ) : LocationReader {
        var fixes = 0
        override suspend fun ensurePermission(): Boolean = permission
        override suspend fun freshFix(timeoutMs: Long): LocationFix? { fixes++; return fix }
    }

    private class FakeBiometrics(var result: BiometricResult = BiometricResult.SUCCESS) : BiometricAuthenticator {
        var prompts = 0
        override fun kind(): BiometricKind = BiometricKind.FINGERPRINT
        override suspend fun authenticate(reason: String): BiometricResult { prompts++; return result }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    /** The device's three facts, in memory. */
    private class FakePrefs(var seen: Boolean = true, private val id: String = "install-1") : CheckInPrefs {
        override suspend fun installId(): String = id
        override suspend fun rationaleSeen(): Boolean = seen
        override suspend fun markRationaleSeen() { seen = true }
        override fun language(): String = "ar"
    }

    private fun viewModel(
        gateway: FakeGateway,
        location: FakeLocation = FakeLocation(),
        biometrics: FakeBiometrics = FakeBiometrics(),
        prefs: FakePrefs = FakePrefs(),
    ) = CheckInViewModel(gateway, location, biometrics, prefs).also { built += it }

    private suspend fun <S : MviState> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    /** The loaded card, ready for a tap. */
    private suspend fun CheckInViewModel.loaded(): CheckInViewModel {
        dispatch(CheckInContract.Intent.Load)
        settle(state) { !it.loading }
        return this
    }

    // ---- 1. a recorded check-in

    @Test fun `a check-in is recorded with the server's time and its lateness`() = runBlocking {
        val gateway = FakeGateway(answer = CheckInResult(CheckInOutcome.RECORDED, serverTime = "07:04", lateMinutes = 12))
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.Recorded("07:04", 12), vm.state.value.message)
        assertEquals(1, gateway.checkIns.size)
        // The words, in both languages, come from the table and not from the view model.
        assertEquals("Checked in at 07:04 — 12 minutes late", StaffStrings.en.checkInMessage(vm.state.value.message!!))
        assertEquals("سُجّل حضورك 07:04 — متأخر 12 دقيقة", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    @Test fun `on time is the lateness of zero, not a separate answer`() = runBlocking {
        val gateway = FakeGateway(answer = CheckInResult(CheckInOutcome.RECORDED, serverTime = "06:52", lateMinutes = 0))
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals("Checked in at 06:52 — on time", StaffStrings.en.checkInMessage(vm.state.value.message!!))
        assertEquals("سُجّل حضورك 06:52 — في الوقت", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    // ---- 2. the refusals, which are all `200`s

    @Test fun `outside the radius says how far away she is`() = runBlocking {
        val gateway = FakeGateway(
            answer = CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.OUTSIDE, distanceMeters = 400),
        )
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.Outside(400), vm.state.value.message)
        assertEquals("أنت على بعد 400 م من المدرسة.", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    @Test fun `an unapproved device names the remedy, which is the Admin's`() = runBlocking {
        val gateway = FakeGateway(
            answer = CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.DEVICE_NOT_APPROVED),
        )
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.DeviceNotApproved, vm.state.value.message)
        assertEquals(
            "هذا الجوال غير معتمد. اطلب من الإدارة اعتماد الجهاز الجديد.",
            StaffStrings.ar.checkInMessage(vm.state.value.message!!),
        )
    }

    @Test fun `a second tap on the same day repeats the first time and changes nothing`() = runBlocking {
        val gateway = FakeGateway(answer = CheckInResult(CheckInOutcome.ALREADY, serverTime = "06:52"))
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.Already("06:52"), vm.state.value.message)
        assertEquals("سجّلت حضورك الساعة 06:52.", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    // ---- 3. what the app decides on its own: only that no fix arrived

    @Test fun `no fix inside the timeout sends nothing and asks her to try again`() = runBlocking {
        val gateway = FakeGateway()
        val vm = viewModel(gateway, location = FakeLocation(fix = null)).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.WeakAccuracy, vm.state.value.message)
        assertTrue(gateway.checkIns.isEmpty())
        assertEquals("دقة الموقع ضعيفة، حاول مرة أخرى.", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    @Test fun `a refused permission sends nothing`() = runBlocking {
        val gateway = FakeGateway()
        val location = FakeLocation(permission = false)
        val vm = viewModel(gateway, location = location).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.NoPermission, vm.state.value.message)
        assertEquals(0, location.fixes)
        assertTrue(gateway.checkIns.isEmpty())
    }

    /**
     * The biometric is the **last** step before the request, so cancelling it leaves no trace at all: nothing is
     * sent, and nothing is said either — she knows she cancelled.
     */
    @Test fun `a cancelled biometric sends nothing and says nothing`() = runBlocking {
        val gateway = FakeGateway()
        val biometrics = FakeBiometrics(BiometricResult.CANCELLED)
        val vm = viewModel(gateway, biometrics = biometrics).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { !it.busy }

        assertEquals(1, biometrics.prompts)
        assertTrue(gateway.checkIns.isEmpty())
        assertNull(vm.state.value.message)
    }

    /** The app never judges the fix: a mocked one goes up as it was read, and the server refuses it. */
    @Test fun `the mock flag and the accuracy are forwarded untouched`() = runBlocking {
        val gateway = FakeGateway(
            answer = CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.MOCK_LOCATION),
        )
        val location = FakeLocation(fix = LocationFix(24.5, 46.1, accuracyMeters = 820.0, mocked = true))
        val vm = viewModel(gateway, location = location).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        val sent = gateway.checkIns.single()
        assertTrue(sent.mocked)
        assertEquals(820.0, sent.accuracyMeters)
        assertEquals(24.5, sent.latitude)
        // The install id is this install's own, generated once and sent on every attempt.
        assertEquals("install-1", sent.installId)
        assertTrue(sent.phoneTime?.isNotBlank() == true)
        assertEquals(CheckInMessage.MockLocation, vm.state.value.message)
    }

    // ---- 4. the network

    @Test fun `no internet is its own message, and no offline queue`() = runBlocking {
        val gateway = FakeGateway(failure = ApiException(ApiError(ApiError.NETWORK, "no route to host")))
        val vm = viewModel(gateway)
        vm.dispatch(CheckInContract.Intent.Load)
        settle(vm.state) { !it.loading }

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null && !it.busy }

        assertEquals(CheckInMessage.Offline, vm.state.value.message)
        assertEquals("تحتاج اتصالًا بالإنترنت لتسجيل الحضور.", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    // ---- 5. check-out

    @Test fun `after a check-in the button checks out`() = runBlocking {
        val gateway = FakeGateway(
            today = CheckInToday(date = "2026-10-14", checkIn = "06:52", lateMinutes = 0),
            answer = CheckInResult(CheckInOutcome.RECORDED, serverTime = "14:03"),
        )
        val vm = viewModel(gateway).loaded()
        assertTrue(vm.state.value.checkingOut)

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertTrue(gateway.checkIns.isEmpty())
        assertEquals(1, gateway.checkOuts.size)
        assertEquals(CheckInMessage.CheckedOut("14:03"), vm.state.value.message)
        assertEquals("سُجّل انصرافك 14:03", StaffStrings.ar.checkInMessage(vm.state.value.message!!))
    }

    @Test fun `a check-out with no check-in is the server's refusal, not the app's`() = runBlocking {
        val gateway = FakeGateway(
            today = CheckInToday(date = "2026-10-14", checkIn = "06:52"),
            answer = CheckInResult(CheckInOutcome.REFUSED, reason = CheckInRefusal.NOT_CHECKED_IN),
        )
        val vm = viewModel(gateway).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.message != null }

        assertEquals(CheckInMessage.NotCheckedIn, vm.state.value.message)
    }

    // ---- 6. the rationale, shown once

    @Test fun `the first tap shows the privacy rationale and takes no fix until she continues`() = runBlocking {
        val gateway = FakeGateway()
        val location = FakeLocation()
        val prefs = FakePrefs(seen = false)
        val vm = viewModel(gateway, location = location, prefs = prefs).loaded()

        vm.dispatch(CheckInContract.Intent.Tap)
        settle(vm.state) { it.rationale }
        assertEquals(0, location.fixes)
        assertTrue(gateway.checkIns.isEmpty())

        vm.dispatch(CheckInContract.Intent.RationaleAccepted)
        settle(vm.state) { it.message != null }
        assertEquals(1, gateway.checkIns.size)
        // Read once: the bit is stored, so the next tap goes straight to the fix.
        assertTrue(prefs.rationaleSeen())
        assertEquals(
            "نقرأ موقعك لحظة التسجيل فقط ولا نحتفظ به.",
            StaffStrings.ar.privacyBody,
        )
    }

    // ---- 7. my record

    @Test fun `my record shows the month the server answered with`() = runBlocking {
        val gateway = FakeGateway(
            month = CheckInMonth(
                "2026-10",
                listOf(
                    CheckInDay(date = "2026-10-13", checkIn = "06:50", checkOut = "14:00", status = "ON_TIME", lateMinutes = 0, mode = "onsite"),
                    CheckInDay(date = "2026-10-14", status = "DAY_OFF"),
                ),
            ),
        )
        val vm = MyRecordViewModel(gateway).also { built += it }
        vm.dispatch(MyRecordContract.Intent.Load)
        settle(vm.state) { !it.loading }

        assertEquals("2026-10", vm.state.value.month)
        assertEquals(listOf("2026-10-13", "2026-10-14"), vm.state.value.days.map { it.date })
        // The first ask names no month: the current one is the server's to decide.
        assertEquals(listOf<String?>(null), gateway.monthsAsked)
        assertEquals("يوم عطلة", StaffStrings.ar.statusName("DAY_OFF"))
        assertEquals("On site", StaffStrings.en.modeName("onsite"))
    }

    @Test fun `the previous month steps back from the month on screen`() = runBlocking {
        val gateway = FakeGateway(month = CheckInMonth("2026-01", emptyList()))
        val vm = MyRecordViewModel(gateway).also { built += it }
        vm.dispatch(MyRecordContract.Intent.Load)
        settle(vm.state) { !it.loading }

        vm.dispatch(MyRecordContract.Intent.PreviousMonth)
        settle(vm.state) { gateway.monthsAsked.size == 2 }

        assertEquals("2025-12", gateway.monthsAsked.last())
    }
}
