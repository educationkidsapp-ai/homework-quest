package quest.feature.auth.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.validation.Sha256
import quest.core.db.SettingsStore

/**
 * Stand-in for Firebase Authentication while the backend does not exist. Any email + password (≥ 6 chars)
 * signs in; the uid is derived from the email so the same "account" comes back after a restart.
 */
class FakeAuth(private val settings: SettingsStore) : AuthProvider {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state

    suspend fun restore() {
        val saved = settings.get(SettingsStore.KEY_FAKE_UID)
        _state.value = if (saved == null) AuthState.SignedOut else AuthState.SignedIn(saved.substringBefore('|'), saved.substringAfter('|'))
    }

    override suspend fun signIn(email: String, password: String) {
        delay(600)
        require(email.contains('@')) { "Please enter a valid email address." }
        require(password.length >= 6) { "The password needs at least 6 characters." }
        val uid = "fake-" + Sha256.hex(email.trim().lowercase()).take(16)
        settings.set(SettingsStore.KEY_FAKE_UID, "$uid|${email.trim()}")
        _state.value = AuthState.SignedIn(uid, email.trim())
    }

    override suspend fun register(email: String, password: String) = signIn(email, password)

    override suspend fun signInWithGoogle() = signIn("parent@gmail.com", "google-sign-in")

    override suspend fun signOut() {
        settings.set(SettingsStore.KEY_FAKE_UID, null)
        settings.setCurrentChild(null)
        _state.value = AuthState.SignedOut
    }

    override suspend fun idToken(forceRefresh: Boolean): String? = (state.value as? AuthState.SignedIn)?.let { "fake-token-${it.uid}" }
}
