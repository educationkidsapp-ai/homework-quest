package quest.feature.lock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
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
@OptIn(ExperimentalCoroutinesApi::class)
class AppLockTest {

    /**
     * [kind] null is "no biometric can be used right now" — none enrolled, locked out, or switched off for the app.
     * The prompt itself then still works through the device [passcode], as the system's sheet does; only a device
     * without one cannot prompt at all. [gate], when set, holds a prompt open until the test releases it.
     */
    private class FakeAuthenticator(var kind: BiometricKind? = BiometricKind.FACE, var next: BiometricResult = BiometricResult.SUCCESS, var passcode: Boolean = true) : BiometricAuthenticator {
        val reasons = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var open = 0; var mostOpenAtOnce = 0
        override fun kind(): BiometricKind? = kind
        override suspend fun authenticate(reason: String): BiometricResult {
            reasons += reason
            open++; mostOpenAtOnce = maxOf(mostOpenAtOnce, open)
            try { gate?.await() } finally { open-- }
            return if (kind == null && !passcode) BiometricResult.UNAVAILABLE else next
        }
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
    /** The monotonic clock the lock measures time away on; the wall clock plays no part. */
    private var clock = 1_000_000L
    private var signOuts = 0
    private val lock = AppLock(auth, preferences, authenticator, signOut = { signOuts++; preferences.signedOut(); auth.signOut() }, elapsed = { clock })

    private suspend fun enable() { lock.signedIn(); lock.acceptOffer("Unlock") }

    // ---------------------------------------------------------------- the offer

    @Test fun theOfferComesOncePerSignIn() = runTest {
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
        assertEquals(BiometricKind.FACE, lock.state.value.kind)

        lock.declineOffer()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertFalse(lock.enabled())

        lock.signedIn()                                   // the same session: not asked twice
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    /** Signing out forgets the answer, so the same account's next sign-in is a first sign-in again. */
    @Test fun afterSigningOutTheSameAccountIsOfferedItAgain() = runTest {
        lock.signedIn(); lock.declineOffer()
        preferences.signedOut(); auth.signOut()

        auth.signIn("u1@example.com", "secret")
        assertEquals(BiometricChoice.NOT_ASKED, preferences.choice("u1"))
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)

        // …and the same after having had it on: off with the session, offered with the next one.
        lock.acceptOffer("Unlock")
        assertTrue(lock.enabled())
        preferences.signedOut(); auth.signOut()
        auth.signIn("u1@example.com", "secret")
        assertFalse(lock.enabled())
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
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

    @Test fun aDeviceWithoutABiometricIsNeverOfferedTheLock() = runTest {
        val bare = AppLock(Auth("u9"), preferences, FakeAuthenticator(kind = null), signOut = {}, elapsed = { clock })
        bare.signedIn()
        assertEquals(Stage.UNLOCKED, bare.state.value.stage, "no offer, no error — silently skipped")
        assertNull(bare.available())
        bare.coldStart()
        assertEquals(Stage.UNLOCKED, bare.state.value.stage)
    }

    /**
     * Review point 1: a lock that was turned on always locks. Face ID locked out, switched off for the app, or
     * removed from the device changes how it is opened — never whether it is shut.
     */
    @Test fun aLockThatIsOnStillLocksWhenTheBiometricCannotBeUsed() = runTest {
        enable()
        authenticator.kind = null                         // lockout / switched off in Settings / none enrolled any more
        assertTrue(lock.enabled(), "the choice stands; only the way in changes")

        lock.coldStart()
        assertEquals(Stage.LOCKED, lock.state.value.stage)
        assertNull(lock.state.value.kind)

        lock.background(); clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1
        lock.unlock("Unlock")                             // the system sheet falls back to the device passcode
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.foreground()
        assertEquals(Stage.LOCKED, lock.state.value.stage, "and it locks again after a minute away, biometric or not")
    }

    @Test fun whereEvenThePasscodeIsImpossibleTheLockScreenStillOffersBothWaysOn() = runTest {
        enable()
        authenticator.kind = null; authenticator.passcode = false
        lock.coldStart()
        lock.unlock("Unlock")
        assertEquals(Stage.LOCKED, lock.state.value.stage, "never open by default")
        assertTrue(lock.state.value.failed, "Try again and Sign in with password are both shown")
        lock.usePassword()
        assertEquals(1, signOuts)
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun theParentAreaAlsoAsksWhenTheBiometricCannotBeUsed() = runTest {
        enable()
        authenticator.kind = null
        assertTrue(lock.confirmOwner("Open the parent area"), "confirmed through the passcode fallback")
        authenticator.passcode = false
        assertFalse(lock.confirmOwner("Open the parent area"), "impossible: the PIN is the way in")
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

    /** Review point 2: time away is measured on a monotonic clock, so moving the wall clock back buys nothing. */
    @Test fun theSixtySecondsAreMeasuredOnTheMonotonicClockOnly() = runTest {
        var wall = 5_000_000L
        val own = AppLock(auth, preferences, authenticator, signOut = {}, elapsed = { clock })
        own.signedIn(); own.acceptOffer("Unlock")
        own.background()
        wall -= 3_600_000L                                // the user sets the device's date back an hour while away
        clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1      // real time, sleep included, still passed
        own.foreground()
        assertEquals(Stage.LOCKED, own.state.value.stage)
        assertTrue(wall < 5_000_000L)
    }

    /** Review point 3: the cover is up the moment the app leaves, and the re-lock is decided in the returning call. */
    @Test fun theCoverGoesUpAtOnceOnLeavingAndComesDownOnAShortReturn() = runTest {
        enable()
        lock.cover()                                      // ON_PAUSE — not a suspend call: nothing is awaited
        assertTrue(lock.state.value.covered)
        lock.background()                                 // ON_STOP
        clock += 5_000
        lock.foreground()                                 // ON_START — also synchronous
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertTrue(lock.state.value.covered, "still covered until the app is interactive again")
        lock.uncover()                                    // ON_RESUME
        assertFalse(lock.state.value.covered)
    }

    @Test fun aLongAbsenceIsLockedBeforeTheCoverComesDown() = runTest {
        enable()
        lock.cover(); lock.background()
        clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1
        lock.foreground()
        assertEquals(Stage.LOCKED, lock.state.value.stage, "decided in the same call, before any frame of the return")
        lock.uncover()
        assertEquals(Stage.LOCKED, lock.state.value.stage)
    }

    /** Re-review point 1: the armed state belongs to the signed-in account whose choice is ENABLED, and to nobody else. */
    @Test fun afterSigningOutTheSignInScreenIsNeitherCoveredNorLocked() = runTest {
        enable()
        preferences.signedOut(); auth.signOut()           // an ordinary sign-out button, not the lock screen's
        lock.cover(); lock.background()
        assertFalse(lock.state.value.covered)
        clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1
        lock.foreground(); lock.uncover()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun anExpiredSessionDisarmsTheLock() = runTest {
        enable()
        auth.flow.value = AuthState.SignedOut             // the session expired; nothing called the lock
        lock.cover(); lock.background(); clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1; lock.foreground()
        assertFalse(lock.state.value.covered)
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun theNextAccountThatDeclinesIsNeverLockedByThePreviousOnesChoice() = runTest {
        enable()                                          // account u1 turned the lock on
        auth.flow.value = AuthState.SignedIn("u2", "u2@example.com")   // u1's session ended; u2 signs in on the same phone
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
        lock.declineOffer()

        lock.cover(); lock.background()
        assertFalse(lock.state.value.covered)
        clock += 10 * AppLock.BACKGROUND_LIMIT_MILLIS
        lock.foreground()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    /** The same account, signed out normally and back in: "Not now" means not locked, whatever it chose last time. */
    @Test fun theSameAccountThatDeclinesAfterSigningBackInIsNotLocked() = runTest {
        enable()
        preferences.signedOut(); auth.signOut()
        auth.signIn("u1@example.com", "secret")
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
        lock.declineOffer()

        lock.cover(); lock.background()
        assertFalse(lock.state.value.covered)
        clock += 10 * AppLock.BACKGROUND_LIMIT_MILLIS
        lock.foreground()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun signingInArmsOnlyAStoredEnabledChoice() = runTest {
        enable()                                          // stored ENABLED for u1 and armed
        auth.flow.value = AuthState.SignedOut; auth.signIn("u1@example.com", "secret")
        lock.signedIn()                                   // the choice is still stored (no sign-out call ran): armed again
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.background(); clock += AppLock.BACKGROUND_LIMIT_MILLIS + 1; lock.foreground()
        assertEquals(Stage.LOCKED, lock.state.value.stage)
    }

    @Test fun anAppWithoutTheLockIsNeverCovered() = runTest {
        lock.signedIn(); lock.declineOffer()
        lock.cover(); lock.background()
        assertFalse(lock.state.value.covered)
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

        // Signing in again with the password: the lock is off, nothing is locked, and the offer is made again.
        auth.signIn("u1@example.com", "secret")
        assertFalse(lock.enabled())
        lock.coldStart()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        lock.signedIn()
        assertEquals(Stage.OFFER, lock.state.value.stage)
    }

    /** Review point 4: one system prompt at a time, and the parent area waits for the app's own lock. */
    @Test fun twoUnlocksShowOnePromptAndTheSecondDoesNothingOnceOpen() = runTest {
        enable(); lock.coldStart()
        val before = authenticator.reasons.size
        authenticator.gate = CompletableDeferred()
        val first = launch { lock.unlock("Unlock") }      // the lock screen's own prompt
        val second = launch { lock.unlock("Unlock") }     // "Try again", or the screen being recreated
        runCurrent()
        assertEquals(1, authenticator.open, "the second waits")
        assertTrue(lock.state.value.prompting)
        authenticator.gate!!.complete(Unit)
        first.join(); second.join()
        assertEquals(1, authenticator.reasons.size - before, "and then finds the app open and prompts for nothing")
        assertEquals(1, authenticator.mostOpenAtOnce)
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
    }

    @Test fun theParentAreaPromptWaitsForTheAppToBeUnlocked() = runTest {
        enable(); lock.coldStart()                        // locked, with the PIN route restored underneath
        authenticator.gate = CompletableDeferred()
        var confirmed: Boolean? = null
        val parentGate = launch { confirmed = lock.confirmOwner("Open the parent area") }
        runCurrent()
        assertEquals(0, authenticator.open, "no prompt alongside the lock screen")

        val unlocking = launch { lock.unlock("Unlock") }
        runCurrent()
        assertEquals(listOf("Unlock"), authenticator.reasons.takeLast(1))
        authenticator.gate!!.complete(Unit); authenticator.gate = null
        unlocking.join(); parentGate.join()
        assertEquals(Stage.UNLOCKED, lock.state.value.stage)
        assertEquals(true, confirmed)
        assertEquals("Open the parent area", authenticator.reasons.last())
        assertEquals(1, authenticator.mostOpenAtOnce)
    }

    @Test fun aCancelledUnlockWithAParentPromptWaitingStillShowsBothButtons() = runTest {
        enable(); lock.coldStart()
        val parentGate = launch { lock.confirmOwner("Open the parent area") }
        runCurrent()
        authenticator.next = BiometricResult.CANCELLED
        lock.unlock("Unlock")
        assertEquals(Stage.LOCKED, lock.state.value.stage)
        assertTrue(lock.state.value.failed)
        assertFalse(lock.state.value.prompting, "the buttons are usable: nothing else is prompting")
        parentGate.cancel()
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
        val other = AppLock(Auth("u2"), preferences, authenticator, signOut = {}, elapsed = { clock })
        assertFalse(other.enabled())
        other.coldStart()
        assertEquals(Stage.UNLOCKED, other.state.value.stage)
        other.signedIn()
        assertEquals(Stage.OFFER, other.state.value.stage)
    }

    @Test fun signingOutForgetsTheChoice() = runTest {
        enable()
        preferences.signedOut()
        assertEquals(BiometricChoice.NOT_ASKED, preferences.choice("u1"))
        preferences.signedOut()                           // twice is harmless, and so is signing out with nothing stored
        assertEquals(BiometricChoice.NOT_ASKED, preferences.choice("u1"))
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
