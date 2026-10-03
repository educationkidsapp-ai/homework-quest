package quest.feature.push.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.DevicePlatform
import quest.api.dto.RegisterDeviceRequest
import quest.core.runCancellable

/** The server half: B4's `POST /me/devices` and `DELETE /me/devices/{token}`, with the parent's own bearer. */
interface PushRegistrar {
    suspend fun register(device: RegisterDeviceRequest)
    suspend fun unregister(token: String)
}

/**
 * The platform half: this device's push token. [platform] is null where there is no push at all — iOS until the
 * school has an Apple developer account, the desktop app, and an Android build without a `google-services.json` — and
 * then nothing is ever registered.
 */
interface PushTokens {
    val platform: DevicePlatform?
    /** The installed build's version name, sent with the token so the server can tell old apps apart. */
    val appVersion: String? get() = null
    suspend fun token(): String?
    /** Forgets the token on the device, so a message sent to it afterwards is never delivered. */
    suspend fun delete()
}

object NoPushTokens : PushTokens {
    override val platform: DevicePlatform? = null
    override suspend fun token(): String? = null
    override suspend fun delete() = Unit
}

/** What the device remembers about push between launches. */
interface PushPreferences {
    /** The token the server last accepted for this account, so a sign-out can withdraw exactly that one. */
    suspend fun registeredToken(): String?
    suspend fun setRegisteredToken(token: String?)
    /** The parent answered the "Turn on notifications" card (either way); it is not shown again. */
    suspend fun promptAnswered(): Boolean
    suspend fun setPromptAnswered()
}

/**
 * M5 — the push token's life, every rule in one place so it can be tested without a device:
 *
 * - **Never for a signed-out app.** Every registration checks the session first; a token the platform hands over while
 *   nobody is signed in (`onNewToken` at first launch) is not sent anywhere.
 * - **Registered after sign-in, at every launch of a signed-in app, on every new token and when the parent changes the
 *   app's language** (the server writes the push's title and body in [locale]'s language). The call is an upsert on the
 *   server, so repeating it costs nothing. A failure (offline) leaves [Status.PENDING]; the next launch tries again.
 * - **Withdrawn on sign-out** while the session can still say who it is (`DELETE /me/devices/{token}`), then deleted on
 *   the device. **On session expiry** the bearer is gone, so only the device copy is deleted — the server learns the
 *   token is dead from FCM on its next send.
 */
class PushRegistration(
    private val auth: AuthProvider,
    private val registrar: PushRegistrar,
    private val tokens: PushTokens,
    private val preferences: PushPreferences,
    private val locale: () -> String?,
) {
    enum class Status { OFF, PENDING, REGISTERED }

    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()

    private val signedIn: Boolean get() = auth.state.value is AuthState.SignedIn

    /** After a password sign-in, and at every launch of an app that is already signed in. */
    suspend fun signedIn() = mutex.withLock {
        val platform = tokens.platform ?: return@withLock
        if (!signedIn) return@withLock
        val token = runCancellable { tokens.token() }.getOrNull() ?: return@withLock
        send(token, platform)
    }

    /**
     * Registers at once (a launch of a signed-in app) and again whenever [languages] changes, so the next push arrives
     * in the language the parent has just chosen. Runs until [scope] is cancelled.
     */
    fun start(scope: CoroutineScope, languages: Flow<String>): Job = scope.launch { languages.distinctUntilChanged().collect { signedIn() } }

    /** The platform rotated the token (`onNewToken`). The old one, if the server had it, is withdrawn. */
    suspend fun newToken(token: String) = mutex.withLock {
        val platform = tokens.platform ?: return@withLock
        if (!signedIn) return@withLock
        val previous = preferences.registeredToken()
        if (send(token, platform) && previous != null && previous != token) runCancellable { registrar.unregister(previous) }
    }

    /** Called before the session ends, while its bearer still works. */
    suspend fun signingOut() = mutex.withLock {
        val registered = preferences.registeredToken()
        if (registered != null && signedIn) runCancellable { registrar.unregister(registered) }
        forget()
    }

    /** Firebase refused the refresh token: there is no bearer left to withdraw with, so only the device forgets. */
    suspend fun sessionExpired() = mutex.withLock { forget() }

    private suspend fun forget() {
        if (tokens.platform != null) runCancellable { tokens.delete() }
        preferences.setRegisteredToken(null)
        _status.value = Status.OFF
    }

    private suspend fun send(token: String, platform: DevicePlatform): Boolean {
        _status.value = Status.PENDING
        val ok = runCancellable { registrar.register(RegisterDeviceRequest(token, platform, tokens.appVersion, locale())) }.isSuccess
        if (ok) { preferences.setRegisteredToken(token); _status.value = Status.REGISTERED }
        return ok
    }
}

/**
 * M5 — whether the parent home shows the "Turn on notifications" card: only where push exists, only where the
 * platform asks before posting (Android 13+), only while it is not granted, and only until she has answered once —
 * "Not now" is respected, and a "no" in the system dialog is never asked again by the app.
 */
class PushPrompts(private val tokens: PushTokens, private val preferences: PushPreferences) {
    suspend fun shouldAsk(permissionNeeded: Boolean, granted: Boolean): Boolean =
        tokens.platform != null && permissionNeeded && !granted && !preferences.promptAnswered()

    suspend fun answered() = preferences.setPromptAnswered()
}
