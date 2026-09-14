package quest.feature.auth.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import quest.api.AuthProvider
import quest.api.AuthState
import quest.core.db.SettingsStore
import quest.core.platform.Today
import kotlinx.serialization.json.JsonPrimitive

/**
 * Firebase Authentication through its REST API (Identity Toolkit + Secure Token), so every platform shares one
 * implementation and no native SDK is needed. The server verifies the resulting ID tokens with Firebase Admin.
 *
 * The refresh token is kept in [SettingsStore] and exchanged for a fresh ID token a minute before expiry.
 */
class FirebaseAuth(
    private val apiKey: String,
    private val settings: SettingsStore,
    private val client: HttpClient,
    private val identityUrl: String = "https://identitytoolkit.googleapis.com/v1",
    private val tokenUrl: String = "https://securetoken.googleapis.com/v1",
) : AuthProvider, SessionRestorer {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val state: StateFlow<AuthState> = _state
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private var idToken: String? = null
    private var expiresAt = 0L
    private var refreshToken: String? = null

    override suspend fun restore() {
        val saved = settings.get(SettingsStore.KEY_FIREBASE_SESSION)?.split('|', limit = 3)
        if (saved == null || saved.size < 3) { _state.value = AuthState.SignedOut; return }
        refreshToken = saved[0]
        _state.value = AuthState.SignedIn(saved[1], saved[2])
    }

    override suspend fun signIn(email: String, password: String) = authenticate("accounts:signInWithPassword", email, password)
    override suspend fun register(email: String, password: String) = authenticate("accounts:signUp", email, password)

    override suspend fun signInWithGoogle() {
        throw IllegalStateException("Google sign-in is not available in this build yet — please use your email and password.")
    }

    override suspend fun signOut() {
        lock.withLock { idToken = null; refreshToken = null; expiresAt = 0 }
        settings.set(SettingsStore.KEY_FIREBASE_SESSION, null)
        settings.setCurrentChild(null)
        _state.value = AuthState.SignedOut
    }

    override suspend fun idToken(forceRefresh: Boolean): String? = lock.withLock {
        val refresh = refreshToken ?: return null
        val fresh = idToken?.takeIf { !forceRefresh && Today.epochMillis() < expiresAt - 60_000 }
        if (fresh != null) return fresh
        val r = client.post("$tokenUrl/token") {
            parameter("key", apiKey)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=refresh_token&refresh_token=$refresh")
        }
        if (!r.status.isSuccess()) { if (r.status.value == 400) signOutLocally(); return null }
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        remember(body["id_token"]!!.jsonPrimitive.content, body["refresh_token"]!!.jsonPrimitive.content, body["expires_in"]!!.jsonPrimitive.content.toLong())
        idToken
    }

    private suspend fun authenticate(method: String, email: String, password: String) {
        require(email.contains('@')) { "Please enter a valid email address." }
        require(password.length >= 6) { "The password needs at least 6 characters." }
        val r = client.post("$identityUrl/$method") {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"email":${JsonPrimitive(email.trim())},"password":${JsonPrimitive(password)},"returnSecureToken":true}""")
        }
        val body = json.parseToJsonElement(r.bodyAsText()).jsonObject
        if (!r.status.isSuccess()) throw IllegalStateException(friendly(body["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content))
        val uid = body["localId"]!!.jsonPrimitive.content
        val mail = body["email"]?.jsonPrimitive?.content ?: email.trim()
        lock.withLock { remember(body["idToken"]!!.jsonPrimitive.content, body["refreshToken"]!!.jsonPrimitive.content, body["expiresIn"]!!.jsonPrimitive.content.toLong()) }
        settings.set(SettingsStore.KEY_FIREBASE_SESSION, "${refreshToken}|$uid|$mail")
        _state.value = AuthState.SignedIn(uid, mail)
    }

    private fun remember(token: String, refresh: String, expiresInSeconds: Long) {
        idToken = token; refreshToken = refresh; expiresAt = Today.epochMillis() + expiresInSeconds * 1000
    }

    private suspend fun signOutLocally() {
        idToken = null; refreshToken = null
        settings.set(SettingsStore.KEY_FIREBASE_SESSION, null)
        _state.value = AuthState.SignedOut
    }

    private fun friendly(code: String?): String = when {
        code == null -> "Could not sign in."
        code.startsWith("EMAIL_EXISTS") -> "That email already has an account — sign in instead."
        code.startsWith("EMAIL_NOT_FOUND") || code.startsWith("INVALID_LOGIN_CREDENTIALS") || code.startsWith("INVALID_PASSWORD") -> "Wrong email or password."
        code.startsWith("WEAK_PASSWORD") -> "The password needs at least 6 characters."
        code.startsWith("INVALID_EMAIL") -> "Please enter a valid email address."
        code.startsWith("TOO_MANY_ATTEMPTS") -> "Too many attempts — please try again in a few minutes."
        code.startsWith("USER_DISABLED") -> "This account has been disabled."
        else -> "Could not sign in ($code)."
    }
}

/** Both auth providers restore a persisted session before the first screen. */
interface SessionRestorer { suspend fun restore() }
