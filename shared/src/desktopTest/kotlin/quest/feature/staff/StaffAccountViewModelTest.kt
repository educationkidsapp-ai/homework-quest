package quest.feature.staff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.ApiException
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ApiError
import quest.core.mvi.MviState
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult
import quest.feature.auth.domain.SessionRoles
import quest.feature.auth.domain.SignIn
import quest.feature.lock.domain.AppLock
import quest.feature.lock.domain.BiometricChoice
import quest.feature.lock.domain.BiometricPreferences
import quest.feature.parent.domain.Appearance
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.staff.domain.SessionSide
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.domain.StaffSessionView
import quest.feature.staff.presentation.StaffAccountContract
import quest.feature.staff.presentation.StaffAccountViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 19: the after-hours switch in her Account.
 *
 * The rollback is the test that matters. The switch moves before the request so it does not feel broken, which means
 * a refused save would otherwise leave her looking at a choice the server never took — and she would find out at
 * nine in the evening.
 *
 * `CheckInViewModelTest`'s rule applies: each view model's loop runs on `Dispatchers.Main`, so each is cancelled
 * before Main is reset.
 */
class StaffAccountViewModelTest {

    private class FakeSession(alerts: Boolean, flagOn: Boolean = true) : StaffSessionView {
        private val _profile = MutableStateFlow<StaffProfile?>(profile(alerts, flagOn))
        override val profile: StateFlow<StaffProfile?> = _profile
        override val disabled = MutableStateFlow(false)
        override val flagStore: FlagStore = object : FlagStore {
            override val flags = MutableStateFlow(DEFAULT_FLAGS)
        }

        var refuse: Throwable? = null
        val saved = mutableListOf<Boolean>()

        override suspend fun setAfterHoursAlerts(on: Boolean) {
            refuse?.let { throw it }
            saved += on
            _profile.value = profile(on)
        }

        private companion object {
            fun profile(alerts: Boolean, flagOn: Boolean = true) = StaffProfile(
                userId = "u-1", displayName = "Ms Noor", role = StaffRole.TEACHER, roleWire = "TEACHER", phone = null,
                schoolId = "s-1", schoolName = "Learning International School",
                flags = mapOf(Flags.CHAT_OFFICE_HOURS to flagOn), afterHoursAlerts = alerts,
            )
        }
    }

    private class FakeParent : ParentRepository {
        private val _language = MutableStateFlow("ar")
        override val language: StateFlow<String> = _language
        private val _appearance = MutableStateFlow(Appearance.SYSTEM)
        override val appearance: StateFlow<Appearance> = _appearance
        override suspend fun setAppearance(appearance: Appearance) { _appearance.value = appearance }
        override suspend fun hasPin(): Boolean = true
        override suspend fun setPin(pin: String) = Unit
        override suspend fun verifyPin(pin: String): Boolean = true
        override suspend fun settings(): ParentSettings = ParentSettings("ar")
        override suspend fun setLanguage(code: String) { _language.value = code }
    }

    private object NoRoles : SessionRoles {
        override val sides = MutableStateFlow(setOf(SessionSide.STAFF))
        override val side = MutableStateFlow<SessionSide?>(SessionSide.STAFF)
        override val mustChangeStaffPassword = MutableStateFlow(false)
        override suspend fun signIn(login: String, password: String): SignIn = SignIn()
        override suspend fun choose(side: SessionSide) = Unit
        override suspend fun changeStaffPassword(currentPassword: String, newPassword: String) = Unit
        override suspend fun signOut() = Unit
    }

    /** Nobody is locked out in this test: a signed-in account with no biometric hardware behind it. */
    private object Signed : quest.api.AuthProvider {
        override val state = MutableStateFlow<quest.api.AuthState>(quest.api.AuthState.SignedIn("u-1", "noor@school.test"))
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean): String? = "token"
    }

    private object NoBiometricPrefs : BiometricPreferences {
        override suspend fun choice(uid: String): BiometricChoice = BiometricChoice.NOT_ASKED
        override suspend fun set(uid: String, choice: BiometricChoice) = Unit
        override suspend fun signedOut() = Unit
    }

    private object NoBiometrics : BiometricAuthenticator {
        override fun kind(): BiometricKind? = null
        override suspend fun authenticate(reason: String): BiometricResult = BiometricResult.UNAVAILABLE
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(session: FakeSession) = StaffAccountViewModel(
        session, FakeParent(),
        AppLock(Signed, NoBiometricPrefs, NoBiometrics, signOut = {}, elapsed = { 0L }),
        NoRoles,
    ).also { built += it }

    private suspend fun <S : MviState> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    @Test fun `the switch loads from her profile`() = runBlocking {
        val vm = viewModel(FakeSession(alerts = true))
        vm.dispatch(StaffAccountContract.Intent.Load)
        settle(vm.state) { it.profile != null }
        assertTrue(vm.state.value.afterHoursAlerts)
    }

    @Test fun `turning the alerts on saves it and keeps the server's answer`() = runBlocking {
        val session = FakeSession(alerts = false)
        val vm = viewModel(session)
        vm.dispatch(StaffAccountContract.Intent.Load)
        settle(vm.state) { it.profile != null }

        vm.dispatch(StaffAccountContract.Intent.AfterHoursAlerts(true))
        settle(vm.state) { session.saved.isNotEmpty() }
        settle(vm.state) { it.afterHoursAlerts }

        assertEquals(listOf(true), session.saved)
        assertFalse(vm.state.value.afterHoursSaveFailed)
    }

    @Test fun `a refused save puts the switch back and says so`() = runBlocking {
        val session = FakeSession(alerts = false).apply { refuse = ApiException(ApiError(ApiError.NETWORK, "offline")) }
        val vm = viewModel(session)
        vm.dispatch(StaffAccountContract.Intent.Load)
        settle(vm.state) { it.profile != null }

        vm.dispatch(StaffAccountContract.Intent.AfterHoursAlerts(true))
        settle(vm.state) { it.afterHoursSaveFailed }

        assertFalse(vm.state.value.afterHoursAlerts, "the switch is back where the server left it")
        assertTrue(session.saved.isEmpty())
    }
}
