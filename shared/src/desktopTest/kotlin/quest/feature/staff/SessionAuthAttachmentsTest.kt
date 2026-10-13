package quest.feature.staff

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.BroadcastAttachment
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.core.platform.MediaFiles
import quest.feature.broadcasts.data.AttachmentImageStore
import quest.feature.broadcasts.data.cacheName
import quest.feature.staff.data.SessionAuth
import quest.feature.staff.data.StaffAuth
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 13 (fix): a photo or a PDF in a **staff** conversation is fetched with her bearer.
 *
 * The loaders take an [AuthProvider], and the one the app binds was the parent's (Firebase) — whose `idToken()` is
 * null for a worker or a teacher who signed in on the staff side, so the loader returned before it reached the
 * network and the bubble could only say the photo could not be downloaded. [SessionAuth] answers for whichever side
 * is signed in, and carries the `X-School-Id` an ADMIN's school is resolved from.
 */
class SessionAuthAttachmentsTest {

    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)

    private val id = "staff-att-${kotlin.random.Random.nextInt(1_000_000)}"
    private val photo = BroadcastAttachment("/media/attachments/$id", "note.png", id, "image/png")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val calls = mutableListOf<HttpRequestData>()

    private fun clearCache() = cacheName(photo)?.let { MediaFiles.delete(MediaFiles.pathOf(it)) }

    @BeforeTest fun before() { clearCache(); calls.clear() }

    @AfterTest fun after() { clearCache() }

    /** The parent half of a staff-only session: signed out, with no token to offer. */
    private class SignedOutParent : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean): String? = null
    }

    private class TokenParent : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("uid", "p@x.com"))
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean): String? = "parent-token"
    }

    private fun signInResponse(schoolId: String?) = """
        {"token":"staff-token","email":"t@x.com","expiresAt":4102444800000,"role":"TEACHER",
         ${schoolId?.let { """"schoolId":"$it",""" } ?: ""}"displayName":"Noor","mustChangePassword":false,"refreshToken":"r-1"}
    """.trimIndent()

    private fun staffAuth(schoolId: String?) = StaffAuth(
        baseUrl = "https://api.test", settings = settings,
        engineClient = HttpClient(MockEngine { respond(signInResponse(schoolId), HttpStatusCode.OK, headersOf("Content-Type", "application/json")) }),
    )

    private fun store(auth: SessionAuth) = AttachmentImageStore(
        "https://api.test", auth,
        HttpClient(MockEngine { req -> calls += req; respond(png, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png")) }),
        schoolId = { auth.schoolId },
    )

    @Test fun `a staff session fetches a photo with her own bearer`() = runTest {
        settings.load()
        val staff = staffAuth(schoolId = "s-1")
        staff.signIn("t@x.com", "secret1")
        assertContentEquals(png, store(SessionAuth(SignedOutParent(), staff)).load(photo))
        assertEquals(1, calls.size, "the parent's missing token no longer stops the request")
        assertEquals("Bearer staff-token", calls[0].headers[HttpHeaders.Authorization])
        assertEquals("s-1", calls[0].headers["X-School-Id"])
    }

    /** An ADMIN's token names no school, so there is nothing to send and the server resolves hers as it does for the dashboard. */
    @Test fun `a session with no school sends no school header`() = runTest {
        settings.load()
        val staff = staffAuth(schoolId = null)
        staff.signIn("admin@x.com", "secret1")
        store(SessionAuth(SignedOutParent(), staff)).load(photo)
        assertNull(calls.single().headers["X-School-Id"])
    }

    /** Nothing changes for a parent: her bearer is the Firebase one and she carries no school header. */
    @Test fun `the parent side keeps the firebase bearer`() = runTest {
        settings.load()
        val auth = SessionAuth(TokenParent(), staffAuth(schoolId = "s-1"))
        assertContentEquals(png, store(auth).load(photo))
        assertEquals("Bearer parent-token", calls.single().headers[HttpHeaders.Authorization])
        assertNull(calls.single().headers["X-School-Id"])
    }

    @Test fun `neither side signed in makes no request`() = runTest {
        settings.load()
        assertNull(store(SessionAuth(SignedOutParent(), staffAuth(schoolId = "s-1"))).load(photo))
        assertTrue(calls.isEmpty(), "an anonymous media request would be a 401 at best")
    }
}
