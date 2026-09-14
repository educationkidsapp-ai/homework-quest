package quest.feature.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import quest.api.AuthState
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FirebaseAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The REST Firebase provider against a scripted Identity Toolkit / Secure Token endpoint. */
class FirebaseAuthTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val calls = mutableListOf<HttpRequestData>()

    private fun auth(script: (HttpRequestData) -> Pair<HttpStatusCode, String>) = FirebaseAuth(
        apiKey = "test-key", settings = settings,
        client = HttpClient(MockEngine { req ->
            calls += req
            val (status, body) = script(req)
            respond(body, status, headersOf("Content-Type", "application/json"))
        }),
    )

    private val ok = """{"localId":"uid-1","email":"p@x.com","idToken":"id-1","refreshToken":"r-1","expiresIn":"3600"}"""

    @Test fun signInStoresSessionAndAttachesToken() = runTest {
        settings.load()
        val a = auth { HttpStatusCode.OK to ok }
        a.signIn("p@x.com", "secret1")
        assertEquals(AuthState.SignedIn("uid-1", "p@x.com"), a.state.value)
        assertEquals("id-1", a.idToken())
        assertEquals(1, calls.size, "a fresh token is not refreshed")
        assertTrue(calls[0].url.toString().startsWith("https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=test-key"))
        val sent = (calls[0].body as TextContent).text
        assertTrue(sent.contains("\"email\":\"p@x.com\"") && sent.contains("\"returnSecureToken\":true"), sent)
        assertEquals("r-1|uid-1|p@x.com", settings.get(SettingsStore.KEY_FIREBASE_SESSION))
    }

    @Test fun registerUsesSignUpAndWrongPasswordIsFriendly() = runTest {
        settings.load()
        val a = auth { req ->
            if (req.url.encodedPath.endsWith("accounts:signUp")) HttpStatusCode.OK to ok
            else HttpStatusCode.BadRequest to """{"error":{"code":400,"message":"INVALID_LOGIN_CREDENTIALS"}}"""
        }
        a.register("p@x.com", "secret1")
        assertTrue(calls.last().url.encodedPath.endsWith("accounts:signUp"))
        val e = assertFailsWith<IllegalStateException> { a.signIn("p@x.com", "nope-nope") }
        assertEquals("Wrong email or password.", e.message)
    }

    @Test fun restoredSessionRefreshesTheIdToken() = runTest {
        settings.load()
        settings.set(SettingsStore.KEY_FIREBASE_SESSION, "r-old|uid-1|p@x.com")
        val a = auth { req ->
            assertTrue(req.url.toString().startsWith("https://securetoken.googleapis.com/v1/token?key=test-key"))
            HttpStatusCode.OK to """{"id_token":"id-2","refresh_token":"r-2","expires_in":"3600"}"""
        }
        a.restore()
        assertEquals(AuthState.SignedIn("uid-1", "p@x.com"), a.state.value)
        assertEquals("id-2", a.idToken())
        assertEquals("id-2", a.idToken(), "cached until it nears expiry")
        assertEquals(1, calls.size)
        assertEquals("grant_type=refresh_token&refresh_token=r-old", (calls[0].body as TextContent).text)
    }

    @Test fun revokedRefreshTokenSignsOut() = runTest {
        settings.load()
        settings.set(SettingsStore.KEY_FIREBASE_SESSION, "r-dead|uid-1|p@x.com")
        val a = auth { HttpStatusCode.BadRequest to """{"error":{"code":400,"message":"TOKEN_EXPIRED"}}""" }
        a.restore()
        assertNull(a.idToken())
        assertEquals(AuthState.SignedOut, a.state.value)
        assertNull(settings.get(SettingsStore.KEY_FIREBASE_SESSION))
    }

    @Test fun signOutClearsEverything() = runTest {
        settings.load()
        val a = auth { HttpStatusCode.OK to ok }
        a.signIn("p@x.com", "secret1")
        a.signOut()
        assertEquals(AuthState.SignedOut, a.state.value)
        assertNull(a.idToken())
        assertNull(settings.get(SettingsStore.KEY_FIREBASE_SESSION))
    }
}
