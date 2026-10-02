package quest.feature.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult
import quest.core.platform.DriverFactory
import quest.feature.lock.data.BiometricPreferencesImpl
import quest.feature.lock.domain.AppLock
import quest.feature.lock.domain.AppLock.Stage
import quest.feature.lock.domain.BiometricChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M2 — the rules of the biometric lock, against a fake authenticator and a clock the test moves. The preferences are
 * the real ones over an in-memory database, so "per account" and "off after sign-out" are tested as stored.
 */
class AppLockTest {

    private class FakeAuthenticator(var kind: BiometricKind? = BiometricKind.FACE, var next: BiometricResult = BiometricResult.SUCCESS) : BiometricAuthenticator {
        val reasons = mutableListOf<String>()
        override fun kind(): BiometricKind? = kind
        override suspend fun authenticate(reason: String): BiometricResult { reasons += reason; return if (kind == null) BiometricResult.UNAVAILABLE else next }
    }

    private class Auth(uid: String? = "u1") : AuthProvider {
        val flow = MutableStateFlow<AuthState>(if (uid == null) AuthState.SignedOut else AuthState.SignedIn(uid, "$uid@example.com"))
        override val state: StateFlow<AuthState> = flow
        override suspend fun signIn(email: String, password: String) { flow.value = AuthState.SignedIn(email.substringBefore('@'), email) }
        override suspend fun signOut() { flow.value = AuthState.SignedOut }
        override suspend fun idToken(forceRefresh: Boolean): String? = null
    }

    private val preferences = BiometricPreferencesImpl(SettingsStore(Db(DriverFactory(null))))
    private val authenticator = FakeAuthenticator()
    private val auth = Auth()
    private var clock = 1_000_000L
    private var signOuts = 0
    private val lock = AppLock(auth, preferences, authenticator, signOut = { signOuts++; preferences.signedOut(); auth.signOut() }, now = { clock })

    private suspend fun enable() { lock.signedIn(); lock.acceptOffer("Unlock") }

    // ---------------------------------------------------------------- the offer

    @Test fun theOfferComesOnceAfterASignIn() = runTest {
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
        assertEquals(BiometricKind.FACE, lock.state.value.kind)

        lock.declineOffer()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertFalse(lock.enabled())

        lock.signedIn()                                   // the same account signs in again: not asked twice
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun acceptingCountsOnlyAfterASuccessfulPrompt() = runTest {
        lock.signedIn()
        authenticator.next = BiometricResult.CANCELLED
        lock.acceptOffer("Unlock")
        assertEquals(Stage.OFFER, lock.state.value.stage, "a dismissed prompt leaves the offer open")
        assertFalse(lock.enabled())

        authenticator.next = BiometricResult.SUCCESS
        lock.acceptOffer("Unlock")
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertTrue(lock.enabled())
        assertEquals(listOf("Unlock", "Unlock"), authenticator.reasons)
    }

    @Test fun aDeviceWithoutABiometricIsNeverAskedAndNeverLocked() = runTest {
        enable()
        authenticator.kind = null                         // the owner removed Face ID after turning the lock on
        assertFalse(lock.enabled())
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)

        val bare = AppLock(Auth("u9"), preferences, FakeAuthenticator(kind = null), signOut = {}, now = { clock })
        bare.signedIn()
        assertEquals(Stage.UNLOCKED, bare.state.value.stage, "no offer, no error — silently skipped")
        assertNull(bare.available())
    }

    // ---------------------------------------------------------------- locking

    @Test fun aColdStartIsLockedOnlyWhenTheAccountChoseIt() = runTest {
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        enable()
        lock.coldStart()
        assertEquals(Stage.LOCKED, lock.state.value.stage)
        assertFalse(lock.state.value.failed, "the buttons appear only after a dismissed prompt")
    }

    @Test fun aSignedOutAppIsNotLocked() = runTest {
        enable()
        auth.signOut()
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun moreThanAMinuteAwayLocks_aMinuteOrLessDoesNot() = runTest {
        enable()
        lock.background(); clock += AppLock.BACKGROUND_LIMIT_MILLIS; lock.foreground()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage, "exactly 60 s is not more than 60 s")

        lock.background(); clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1; lock.foreground()
        assertEquals(Stage.LOCKED, lock.state.value.stage)
    }

    @Test fun comingBackWithoutHavingLeftChangesNothing() = runTest {
        enable()
        clock += 10 * AppLock.BACKGROUND_LIMIT_MILLIS
        lock.foreground()                                 // the first ON_START of a launch has no ON_STOP before it
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun anAccountThatDeclinedIsNeverLocked() = runTest {
        lock.signedIn(); lock.declineOffer()
        lock.background(); clock += 10 * AppLock.BACKGROUND_LIMIT_MILLIS; lock.foreground()
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    // ---------------------------------------------------------------- unlocking

    @Test fun aSuccessfulPromptLiftsTheLock() = runTest {
        enable(); lock.coldStart()
        lock.unlock("Unlock MySchool")
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertEquals("Unlock MySchool", authenticator.reasons.last())
    }

    @Test fun aDismissedPromptOffersTryAgainAndThePassword() = runTest {
        enable(); lock.coldStart()
        authenticator.next = BiometricResult.CANCELLED
        lock.unlock("Unlock")
        assertEquals(Stage.LOCKED, lock.state.value.stage)
        assertTrue(lock.state.value.failed)
        assertFalse(lock.state.value.prompting)

        authenticator.next = BiometricResult.SUCCESS      // "Try again"
        lock.unlock("Unlock")
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun signInWithPasswordSignsOutAndTurnsTheLockOff() = runTest {
        enable(); lock.coldStart()
        authenticator.next = BiometricResult.CANCELLED
        lock.unlock("Unlock")
        lock.usePassword()

        assertEquals(1, signOuts)
        assertEquals(AuthState.SignedOut, auth.state.value)
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)

        // Signing in again with the password: the lock is off, and the question is not asked a second time.
        auth.signIn("u1@example.com", "secret")
        assertFalse(lock.enabled())
        lock.signedIn()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun unlockDoesNothingWhenTheAppIsNotLocked() = runTest {
        enable()
        val before = authenticator.reasons.size
        lock.unlock("Unlock")
        assertEquals(before, authenticator.reasons.size)
    }

    // ---------------------------------------------------------------- per account

    @Test fun theChoiceBelongsToTheAccountThatMadeIt() = runTest {
        enable()
        assertEquals(BiometricChoice.ENABLED, preferences.choice("u1"))
        assertEquals(BiometricChoice.NOT_ASKED, preferences.choice("u2"))

        // Another parent signs in on the same phone: she is offered it herself and is not locked by the first one's choice.
        val other = AppLock(Auth("u2"), preferences, authenticator, signOut = {}, now = { clock })
        assertFalse(other.enabled())
        other.coldStart()
        assertEquals(Stage.UNLOCKED, other.state.value.stage)
        other.signedIn()
        assertEquals(Stage.OFFER, other.state.value.stage)
    }

    @Test fun signingOutTurnsTheLockOffButRemembersThatItWasOffered() = runTest {
        enable()
        preferences.signedOut()
        assertEquals(BiometricChoice.DECLINED, preferences.choice("u1"))
        preferences.signedOut()                           // twice is harmless, and so is signing out with nothing stored
        assertEquals(BiometricChoice.DECLINED, preferences.choice("u1"))
        BiometricPreferencesImpl(SettingsStore(Db(DriverFactory(null)))).signedOut()
    }

    // ---------------------------------------------------------------- settings and the parent area

    @Test fun theSettingsSwitchTurnsOnOnlyAfterASuccessfulPrompt() = runTest {
        authenticator.next = BiometricResult.CANCELLED
        assertFalse(lock.setEnabled(true, "Unlock with Face ID"))
        assertFalse(lock.enabled())

        authenticator.next = BiometricResult.SUCCESS
        assertTrue(lock.setEnabled(true, "Unlock with Face ID"))
        assertTrue(lock.enabled())

        val prompts = authenticator.reasons.size
        assertFalse(lock.setEnabled(false, "Unlock with Face ID"))
        assertFalse(lock.enabled())
        assertEquals(prompts, authenticator.reasons.size, "turning it off asks for nothing")
    }

    @Test fun theParentAreaUsesTheSamePromptAndFallsBackToThePin() = runTest {
        assertFalse(lock.confirmOwner("Open the parent area"), "not enabled: straight to the PIN, no prompt")
        assertTrue(authenticator.reasons.isEmpty())

        enable()
        assertTrue(lock.confirmOwner("Open the parent area"))
        assertEquals("Open the parent area", authenticator.reasons.last())

        authenticator.next = BiometricResult.CANCELLED
        assertFalse(lock.confirmOwner("Open the parent area"), "dismissed: the PIN pad is still there")
        assertEquals(Stage.UNLOCKED, lock.state.value.stage, "and the app itself is not locked by it")
    }
}
