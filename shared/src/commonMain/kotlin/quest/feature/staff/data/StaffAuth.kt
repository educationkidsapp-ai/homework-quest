package quest.feature.staff.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dashboard.ChangePasswordRequest
import quest.api.dashboard.RefreshRequest
import quest.api.dashboard.SignInResponse
import quest.api.dashboard.TokenPair
import quest.api.dto.ApiError
import quest.core.db.SettingsStore
import quest.core.json.AppJson
import quest.core.platform.Today
import quest.feature.auth.data.SessionRestorer

/**
 * Feature 13: the **staff** half of the session — `POST /auth/sign-in`, `/auth/refresh` and `/auth/change-password`,
 * the same three the dashboard calls.
 *
 * It is an [AuthProvider] on purpose. Everything in the app that needs a bearer already takes one — the Ktor calls
 * through `authed(auth)`, the `/ws/chat` socket, the attachment image and document loaders — so making the staff
 * session wear the same interface is what lets the staff side reuse all of it instead of growing a second copy.
 * `idToken()` answers the access token and rotates it on `/auth/refresh` a minute before it expires, exactly as
 * `FirebaseAuth` does with the Firebase one.
 *
 * `email` on the wire also takes a worker's generated username (`w.ahmed.4821`), so nothing here validates the shape
 * of a login: that is the server's answer to give.
 */
class StaffAuth(
    private val baseUrl: String,
    private val settings: SettingsStore,
    engineClient: HttpClient,
    private val onSessionExpired: suspend () -> Unit = {},
) : AuthProvider, SessionRestorer {

    private val client = engineClient.config {
        install(ContentNegotiation) { json(AppJson) }
    }

    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state.asStateFlow()

    /** What the last sign-in (or refresh) said about her, for the shell before `GET /staff/me` answers. */
    private val _mustChangePassword = MutableStateFlow(false)
    val mustChangePassword: StateFlow<Boolean> = _mustChangePassword.asStateFlow()

    /**
     * The `schoolId` the sign-in named. An ADMIN's token names no school, and then every staff request carries
     * `X-School-Id` instead — which is why this is public: [RemoteStaffApi] reads it for that header.
     */
    private val _schoolId = MutableStateFlow<String?>(null)
    val schoolId: StateFlow<String?> = _schoolId.asStateFlow()

    private val _role = MutableStateFlow<String?>(null)
    val role: StateFlow<String?> = _role.asStateFlow()

    private val _displayName = MutableStateFlow<String?>(null)
    val displayName: StateFlow<String?> = _displayName.asStateFlow()

    private val lock = Mutex()
    private var accessToken: String? = null
    private var expiresAt = 0L
    private var refreshToken: String? = null

    val signedIn: Boolean get() = _state.value is AuthState.SignedIn

    override suspend fun restore() {
        val saved = settings.get(SettingsStore.KEY_STAFF_SESSION)?.split('|', limit = 2)
        if (saved == null || saved.size < 2 || saved[1].isBlank()) { _state.value = AuthState.SignedOut; return }
        refreshToken = saved[0]
        // The change-password step survives being killed: the flag is read back with the session, because
        // `/auth/refresh` does not repeat it and `GET /staff/me` does not carry it at all.
        _mustChangePassword.value = settings.get(SettingsStore.KEY_STAFF_MUST_CHANGE) == "1"
        // Her login is her identity on this device: the biometric choice and the push token are both keyed by it.
        _state.value = AuthState.SignedIn(saved[1], saved[1])
    }

    override suspend fun signIn(email: String, password: String) {
        val response: HttpResponse = client.post("$baseUrl/auth/sign-in") {
            contentType(ContentType.Application.Json)
            setBody(StaffSignInRequest(email.trim(), password))
        }
        if (!response.status.isSuccess()) throw response.toException()
        val body: SignInResponse = response.body()
        lock.withLock { remember(body.token, body.refreshToken, body.expiresAt) }
        _schoolId.value = body.schoolId
        _role.value = body.role.name
        _displayName.value = body.displayName?.takeIf { it.isNotBlank() } ?: body.email
        rememberMustChange(body.mustChangePassword)
        remembered(body.email)
    }

    /** `POST /auth/change-password`: clears `mustChangePassword` and revokes every other session, so we re-sign in. */
    suspend fun changePassword(currentPassword: String, newPassword: String, login: String) {
        val response = client.post("$baseUrl/auth/change-password") {
            contentType(ContentType.Application.Json)
            authorize()
            setBody(ChangePasswordRequest(currentPassword, newPassword))
        }
        if (!response.status.isSuccess()) throw response.toException()
        // The server revoked this session's refresh family with every other one, so the new password signs in afresh.
        signIn(login, newPassword)
    }

    override suspend fun signOut() {
        lock.withLock { accessToken = null; refreshToken = null; expiresAt = 0 }
        settings.set(SettingsStore.KEY_STAFF_SESSION, null)
        _schoolId.value = null
        _role.value = null
        _displayName.value = null
        rememberMustChange(false)
        _state.value = AuthState.SignedOut
    }

    override suspend fun idToken(forceRefresh: Boolean): String? = lock.withLock {
        if (_state.value !is AuthState.SignedIn) return null
        val fresh = accessToken?.takeIf { !forceRefresh && Today.epochMillis() < expiresAt - 60_000 }
        if (fresh != null) return fresh
        val refresh = refreshToken ?: return accessToken
        val response = client.post("$baseUrl/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refresh))
        }
        if (!response.status.isSuccess()) {
            // A revoked refresh token kills the family: there is nothing left to retry with.
            if (response.status.value == 401 || response.status.value == 403) expire()
            return null
        }
        val pair: TokenPair = response.body()
        remember(pair.token, pair.refreshToken, pair.expiresAt)
        settings.set(SettingsStore.KEY_STAFF_SESSION, "${pair.refreshToken}|${login()}")
        accessToken
    }

    private fun login(): String = (_state.value as? AuthState.SignedIn)?.email.orEmpty()

    private suspend fun rememberMustChange(must: Boolean) {
        _mustChangePassword.value = must
        settings.set(SettingsStore.KEY_STAFF_MUST_CHANGE, if (must) "1" else null)
    }

    private suspend fun remembered(login: String) {
        settings.set(SettingsStore.KEY_STAFF_SESSION, "${refreshToken.orEmpty()}|$login")
        _state.value = AuthState.SignedIn(login, login)
    }

    private fun remember(token: String, refresh: String?, expiresAtMillis: Long) {
        accessToken = token
        refresh?.let { refreshToken = it }
        // `expiresAt` is an absolute epoch stamp on the wire; a server that sent seconds-from-now is still honoured.
        expiresAt = if (expiresAtMillis > 1_000_000_000_000L) expiresAtMillis else Today.epochMillis() + expiresAtMillis * 1000
    }

    private suspend fun expire() {
        accessToken = null
        refreshToken = null
        settings.set(SettingsStore.KEY_STAFF_SESSION, null)
        rememberMustChange(false)
        _state.value = AuthState.SignedOut
        onSessionExpired()
    }

    private fun HttpRequestBuilder.authorize() {
        accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val text = bodyAsText()
        val error = runCatching { AppJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(
                when (status.value) { 401 -> ApiError.UNAUTHORIZED; 403 -> ApiError.FORBIDDEN; 404 -> ApiError.NOT_FOUND; else -> ApiError.NETWORK },
                "Server said ${status.value}",
            )
        return ApiException(error)
    }
}

@kotlinx.serialization.Serializable
private data class StaffSignInRequest(val email: String, val password: String)
