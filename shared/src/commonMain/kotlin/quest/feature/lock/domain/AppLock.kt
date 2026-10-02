package quest.feature.lock.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
 * without a device: offered after every password sign-in until it is answered (signing out forgets the answer), locked on a cold start and after [BACKGROUND_LIMIT_MILLIS] in the
 * background, silent where the device has no biometric, and "Sign in with password" as the way out.
 */
class AppLock(
    private val auth: AuthProvider,
    private val preferences: BiometricPreferences,
    private val authenticator: BiometricAuthenticator,
    private val signOut: suspend () -> Unit,
    private val now: () -> Long,
) {
    enum class Stage { UNLOCKED, LOCKED, OFFER }

    /** [failed] is "the last prompt did not confirm the owner": the lock screen then shows its two buttons. */
    data class State(val stage: Stage = Stage.UNLOCKED, val kind: BiometricKind? = null, val failed: Boolean = false, val prompting: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var backgroundedAt: Long? = null

    private fun uid(): String? = (auth.state.value as? AuthState.SignedIn)?.uid

    /** Whether this account unlocks with a biometric here — false wherever the device has none, whatever was stored. */
    suspend fun enabled(): Boolean {
        val uid = uid() ?: return false
        return authenticator.kind() != null && preferences.choice(uid) == BiometricChoice.ENABLED
    }

    /** The kind the device offers, for the Settings row; null hides the row. */
    fun available(): BiometricKind? = authenticator.kind()

    /** A cold start: a signed-in account that chose the lock starts behind it. */
    suspend fun coldStart() {
        if (enabled()) _state.value = State(Stage.LOCKED, authenticator.kind())
    }

    /** Just signed in with a password: offer the lock once, and only where the device can do it. */
    suspend fun signedIn() {
        val uid = uid() ?: return
        val kind = authenticator.kind() ?: return
        if (preferences.choice(uid) == BiometricChoice.NOT_ASKED) _state.value = State(Stage.OFFER, kind)
    }

    /** "Turn on" in the offer: it counts only after a successful prompt. A cancelled one leaves the offer open. */
    suspend fun acceptOffer(reason: String) {
        val uid = uid() ?: return
        if (prompt(reason) == BiometricResult.SUCCESS) { preferences.set(uid, BiometricChoice.ENABLED); _state.value = State() }
    }

    suspend fun declineOffer() {
        uid()?.let { preferences.set(it, BiometricChoice.DECLINED) }
        _state.value = State()
    }

    /** The lock screen's prompt — shown when it appears, and again by "Try again". */
    suspend fun unlock(reason: String) {
        if (_state.value.stage != Stage.LOCKED || _state.value.prompting) return
        when (prompt(reason)) {
            BiometricResult.SUCCESS -> _state.value = State()
            BiometricResult.CANCELLED -> _state.update { it.copy(failed = true) }
            // The biometric was removed from the device since the lock was chosen: there is nothing to prompt with, so
            // the password is the only way in.
            BiometricResult.UNAVAILABLE -> _state.update { it.copy(failed = true, kind = null) }
        }
    }

    /** "Sign in with password": the session ends (which also turns the lock off) and the sign-in screen takes over. */
    suspend fun usePassword() {
        signOut()
        _state.value = State()
    }

    fun background() { if (backgroundedAt == null) backgroundedAt = now() }

    /** Back in front: more than a minute away locks the app again; a shorter absence does not. */
    suspend fun foreground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (_state.value.stage == Stage.UNLOCKED && now() - since > BACKGROUND_LIMIT_MILLIS && enabled()) _state.value = State(Stage.LOCKED, authenticator.kind())
    }

    /** The Settings switch. Turning it on needs a successful prompt; turning it off does not. Returns the new value. */
    suspend fun setEnabled(on: Boolean, reason: String): Boolean {
        val uid = uid() ?: return false
        if (!on) { preferences.set(uid, BiometricChoice.DECLINED); return false }
        if (authenticator.kind() == null || prompt(reason) != BiometricResult.SUCCESS) return enabled()
        preferences.set(uid, BiometricChoice.ENABLED)
        return true
    }

    /** The parent area's gate: true when the owner was confirmed. False sends the caller to its PIN, the fallback. */
    suspend fun confirmOwner(reason: String): Boolean = enabled() && prompt(reason) == BiometricResult.SUCCESS

    private suspend fun prompt(reason: String): BiometricResult {
        _state.update { it.copy(prompting = true) }
        return try { authenticator.authenticate(reason) } finally { _state.update { it.copy(prompting = false) } }
    }

    companion object { const val BACKGROUND_LIMIT_MILLIS = 60_000L }
}
