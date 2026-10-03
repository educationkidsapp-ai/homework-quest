package quest.feature.parent

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
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dashboard.ParentProfile
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.parent.presentation.SettingsContract
import quest.feature.parent.presentation.SettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MH3, review #175 blocker 4: saving her mobile number has **three** outcomes, not two.
 *
 * Collapsing them lost the number and mislabelled the failure — a valid number typed on a bad connection was wiped
 * from the field and the parent was told it was the wrong shape, which is two lies at once.
 */
class SettingsPhoneTest {
    private val settings = SettingsStore(Db(DriverFactory(null)))

    /** Only the two profile calls matter; the rest of the contract is the fake's, by delegation. */
    private class Api(base: ContentApi, var stored: String? = null, var onUpdate: () -> Unit = {}) : ContentApi by base {
        var sent: String? = null
        override suspend fun parentProfile() = ParentProfile("p1", "parent@example.com", stored)
        override suspend fun updateParentProfile(phone: String?): ParentProfile {
            sent = phone
            onUpdate()
            stored = phone?.takeIf { it.isNotBlank() }
            return ParentProfile("p1", "parent@example.com", stored)
        }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun api(stored: String? = null, onUpdate: () -> Unit = {}) =
        Api(FakeContentApi(FakeAuth(settings), delayMillis = 0), stored, onUpdate)

    private fun viewModel(api: ContentApi, authenticator: BiometricAuthenticator = quest.core.platform.platformBiometricAuthenticator()) =
        SettingsViewModel(ParentRepositoryImpl(settings), api, quest.feature.lock.domain.AppLock(
            FakeAuth(settings), quest.feature.lock.data.BiometricPreferencesImpl(settings), authenticator, signOut = {}, elapsed = { 0L },
        )).also { built.add(it) }

    /** M6: what the phone can unlock with, as the test sets it; the prompt is never reached here. */
    private class Device(var kind: BiometricKind?) : BiometricAuthenticator {
        override fun kind() = kind
        override suspend fun authenticate(reason: String) = BiometricResult.UNAVAILABLE
    }

    /** M6: back from the phone's settings with a screen lock set, the row offers it — no reopening of Settings. */
    @Test fun theLockRowFollowsThePhoneWhenSettingsIsShownAgain() = runBlocking {
        val device = Device(kind = null)
        val vm = viewModel(api(), device)
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(null, vm.state.value.biometricKind, "no screen lock: the row says to set one")

        device.kind = BiometricKind.SCREEN_LOCK
        vm.dispatch(SettingsContract.Intent.RefreshLock)
        settle(vm.state) { it.biometricKind == BiometricKind.SCREEN_LOCK }
        assertFalse(vm.state.value.biometricOn)
    }

    private suspend fun <S> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    @Test fun herNumberIsLoadedAndTheSectionOnlyAppearsOnceItHas() = runBlocking {
        val vm = viewModel(api(stored = "+971501234567"))
        assertFalse(vm.state.value.phoneKnown, "no field until the server has answered")
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }
        assertEquals("+971501234567", vm.state.value.phone)
    }

    /** The happy path: what is sent is the normalised number, and the field shows what the server stored. */
    @Test fun savingSendsTheNormalisedNumber() = runBlocking {
        val api = api()
        val vm = viewModel(api)
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }

        vm.dispatch(SettingsContract.Intent.Phone("050 123 4567"))
        vm.dispatch(SettingsContract.Intent.SavePhone)
        settle(vm.state) { it.phoneSaved }
        assertEquals("0501234567", api.sent)
        assertEquals("0501234567", vm.state.value.phone)
        assertFalse(vm.state.value.phoneInvalid)
        assertFalse(vm.state.value.phoneSaveFailed)
    }

    /** The shape is wrong: marked invalid, no request made, and what she typed is left alone to be corrected. */
    @Test fun aBadShapeIsCaughtWithoutARequest() = runBlocking {
        val api = api()
        val vm = viewModel(api)
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }

        vm.dispatch(SettingsContract.Intent.Phone("050 12"))
        vm.dispatch(SettingsContract.Intent.SavePhone)
        settle(vm.state) { it.phoneInvalid }
        assertEquals(null, api.sent, "the mirror refused it before the round trip")
        assertEquals("050 12", vm.state.value.phone, "hers to fix, so it stays")
        assertFalse(vm.state.value.phoneSaved)
        assertFalse(vm.state.value.phoneSaveFailed)
    }

    /**
     * The blocker. A valid number and a network that is not there: the number **stays in the field**, and she is told
     * the save failed rather than that her number is the wrong shape.
     */
    @Test fun aNetworkFailureKeepsTheNumberAndIsNotCalledInvalid() = runBlocking {
        val vm = viewModel(api(onUpdate = { throw ApiException(ApiError(ApiError.NETWORK, "offline")) }))
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }

        vm.dispatch(SettingsContract.Intent.Phone("+971501234567"))
        vm.dispatch(SettingsContract.Intent.SavePhone)
        settle(vm.state) { it.phoneSaveFailed }
        assertEquals("+971501234567", vm.state.value.phone, "she must not have to type it again")
        assertFalse(vm.state.value.phoneInvalid, "nothing is wrong with the number")
        assertFalse(vm.state.value.phoneSaved)
    }

    /** A rule the mirror does not have: the server refuses it, and *that* is what marks the field invalid. */
    @Test fun aServerRejectionMarksItInvalid() = runBlocking {
        val vm = viewModel(api(onUpdate = { throw ApiException(ApiError(ApiError.BAD_REQUEST, "phone may only begin with a +.")) }))
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }

        vm.dispatch(SettingsContract.Intent.Phone("+971501234567"))
        vm.dispatch(SettingsContract.Intent.SavePhone)
        settle(vm.state) { it.phoneInvalid }
        assertEquals("+971501234567", vm.state.value.phone)
        assertFalse(vm.state.value.phoneSaveFailed)
        assertFalse(vm.state.value.phoneSaved)
    }

    /** Typing again clears every verdict, so a stale "Saved" or error never sits under a number she has changed. */
    @Test fun typingClearsTheLastVerdict() = runBlocking {
        val vm = viewModel(api(onUpdate = { throw ApiException(ApiError(ApiError.NETWORK, "offline")) }))
        vm.dispatch(SettingsContract.Intent.Load)
        settle(vm.state) { it.phoneKnown }
        vm.dispatch(SettingsContract.Intent.Phone("+971501234567"))
        vm.dispatch(SettingsContract.Intent.SavePhone)
        settle(vm.state) { it.phoneSaveFailed }

        vm.dispatch(SettingsContract.Intent.Phone("+9715012345"))
        settle(vm.state) { !it.phoneSaveFailed }
        assertFalse(vm.state.value.phoneInvalid)
        assertTrue(vm.state.value.phone == "+9715012345")
    }
}
