package quest.feature.lock.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.AuthProvider
import quest.api.AuthState
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult

/** What one account has said about unlocking with a biometric on this device. */
enum class BiometricChoice { NOT_ASKED, ENABLED, DECLINED }

/**
 * The choice, kept on the device for the account it was made by. [signedOut] forgets it entirely: the next person to
 * sign in on this phone must not inherit the lock, and the next sign-in — the same account's included — is a first
 * sign-in again, so the offer is made again.
 */
interface BiometricPreferences {
    suspend fun choice(uid: String): BiometricChoice
    suspend fun set(uid: String, choice: BiometricChoice)
    suspend fun signedOut()
}

/**
 * M2 — the biometric lock over the whole app.
 *
 * It is a gate in front of the screens, not part of signing in: the session and its tokens stay exactly where
 * `AuthProvider` keeps them, and a successful prompt only lifts the cover. Every rule is here so it can be tested
 * without a device.
 *
 * **A lock that was turned on always locks.** Whether the device can show a biometric *right now* decides only how it
 * is opened, never whether it is shut: Face ID locked out after failed attempts, switched off for the app in
 * Settings, or removed from the device leaves the app locked, and the prompt falls back to the device passcode; where
 * even that is impossible the lock screen offers "Sign in with password". Availability matters for one thing only —
 * *offering* the lock, which is done where a biometric is enrolled.
 *
 * Time away is measured on [elapsed], a monotonic clock that keeps counting while the device sleeps: the wall clock
 * can be set back by the user, which would otherwise keep an app unlocked for as long as she liked.
 */
class AppLock(
    private val auth: AuthProvider,
    private val preferences: BiometricPreferences,
    private val authenticator: BiometricAuthenticator,
    private val signOut: suspend () -> Unit,
    private val elapsed: () -> Long,
) {
    enum class Stage { UNLOCKED, LOCKED, OFFER }

    /**
     * [failed] is "the last prompt did not confirm the owner": the lock screen then shows its two buttons, always
     * both. [covered] is the plain cover that goes up the instant the app leaves the foreground — so the system's
     * app-switcher picture shows the cover and not the screen — and comes down on return unless the app locked.
     */
    data class State(
        val stage: Stage = Stage.UNLOCKED, val kind: BiometricKind? = null, val failed: Boolean = false, val prompting: Boolean = false, val covered: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** One system prompt at a time, whoever asks: the lock screen, the offer, Settings or the parent area. */
    private val prompts = Mutex()

    /**
     * The account whose stored choice is ENABLED, kept in memory so leaving and returning are decided without I/O.
     * The lock is armed only while **that** account is the one signed in: a sign-out or an expired session disarms it
     * by itself (nobody is signed in), and so does another account signing in — the sign-in screen and an account
     * that declined the offer are never covered or locked by someone else's choice.
     */
    private var armedFor: String? = null
    private val armed: Boolean get() = armedFor != null && armedFor == uid()
    private var backgroundedAt: Long? = null

    private fun uid(): String? = (auth.state.value as? AuthState.SignedIn)?.uid

    private fun arm(on: Boolean) { armedFor = if (on) uid() else null }

    /** Whether this account chose the lock on this device — whatever the device can prompt with at the moment. */
    suspend fun enabled(): Boolean {
        val on = uid()?.let { preferences.choice(it) == BiometricChoice.ENABLED } ?: false
        arm(on)
        return on
    }

    /** The kind the device offers right now, for the offer and the Settings row; null hides both. */
    fun available(): BiometricKind? = authenticator.kind()

    /** A cold start: a signed-in account that chose the lock starts behind it. */
    suspend fun coldStart() {
        if (enabled()) _state.value = State(Stage.LOCKED, authenticator.kind())
    }

    /** Just signed in with a password: offer the lock, and only where the device has a biometric to offer. */
    suspend fun signedIn() {
        val uid = uid() ?: return
        val kind = authenticator.kind() ?: return
        if (preferences.choice(uid) == BiometricChoice.NOT_ASKED) _state.value = State(Stage.OFFER, kind)
    }

    /** "Turn on" in the offer: it counts only after a successful prompt. A cancelled one leaves the offer open. */
    suspend fun acceptOffer(reason: String) {
        val uid = uid() ?: return
        if (prompt(reason) == BiometricResult.SUCCESS) { preferences.set(uid, BiometricChoice.ENABLED); arm(true); _state.value = State() }
    }

    suspend fun declineOffer() {
        uid()?.let { preferences.set(it, BiometricChoice.DECLINED) }
        _state.value = State()
    }

    /**
     * The lock screen's prompt — shown when it appears, and again by "Try again". Calls are serialised: a second one
     * waits for the first and then does nothing if the app is already open. Anything but success leaves the lock
     * screen with both of its buttons.
     */
    suspend fun unlock(reason: String) {
        prompts.withLock {
            if (_state.value.stage != Stage.LOCKED) return
            _state.update { it.copy(prompting = true, kind = authenticator.kind()) }
            val result = try { authenticator.authenticate(reason) } finally { _state.update { it.copy(prompting = false) } }
            if (result == BiometricResult.SUCCESS) _state.value = State() else _state.update { it.copy(failed = true) }
        }
    }

    /** "Sign in with password": the session ends (which also forgets the lock) and the sign-in screen takes over. */
    suspend fun usePassword() {
        signOut()
        arm(false)
        _state.value = State()
    }

    /**
     * The app is leaving the foreground (`ON_PAUSE`): the cover goes up **now**, in the same call, so whatever picture
     * the system takes of the app for its switcher is the cover. Nothing is read or awaited here.
     */
    fun cover() { if (armed && _state.value.stage == Stage.UNLOCKED) _state.update { it.copy(covered = true) } }

    /** The app is not visible any more (`ON_STOP`): the time away starts counting. */
    fun background() {
        cover()
        if (backgroundedAt == null) backgroundedAt = elapsed()
    }

    /**
     * Back in front (`ON_START`), decided synchronously before a frame is drawn: more than a minute away locks the
     * app; a shorter absence does not.
     */
    fun foreground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (armed && _state.value.stage == Stage.UNLOCKED && elapsed() - since > BACKGROUND_LIMIT_MILLIS) _state.value = State(Stage.LOCKED, authenticator.kind())
    }

    /** Interactive again (`ON_RESUME`): the plain cover comes down. A locked app keeps its lock screen. */
    fun uncover() { _state.update { it.copy(covered = false) } }

    /** The Settings switch. Turning it on needs a biometric to offer and a successful prompt; turning it off does not. */
    suspend fun setEnabled(on: Boolean, reason: String): Boolean {
        val uid = uid() ?: return false
        if (!on) { preferences.set(uid, BiometricChoice.DECLINED); arm(false); return false }
        if (authenticator.kind() == null || prompt(reason) != BiometricResult.SUCCESS) return enabled()
        preferences.set(uid, BiometricChoice.ENABLED); arm(true)
        return true
    }

    /**
     * The parent area's gate: true when the owner was confirmed. False sends the caller to its PIN, the fallback. If
     * the app itself is locked — the PIN screen was restored underneath the cover — this waits for that lock to be
     * lifted first, so the two never prompt on top of each other.
     */
    suspend fun confirmOwner(reason: String): Boolean {
        if (!enabled()) return false
        _state.first { it.stage != Stage.LOCKED }
        return prompt(reason) == BiometricResult.SUCCESS
    }

    private suspend fun prompt(reason: String): BiometricResult = prompts.withLock {
        _state.update { it.copy(prompting = true) }
        try { authenticator.authenticate(reason) } finally { _state.update { it.copy(prompting = false) } }
    }

    companion object { const val BACKGROUND_LIMIT_MILLIS = 60_000L }
}
