package quest.feature.journey

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
import quest.api.dto.PageImage
import quest.api.samples.HotSoupSeed
import quest.feature.journey.data.LessonImages
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/media/pages/{id}` is about to require authentication: the app's page-image loader must carry the parent's
 * Firebase ID token, refresh it once when the server says 401, and stay off the network when nobody is signed in.
 */
class LessonImagesTest {
    private val imageId = "lesson-h:page-1"
    private val url = "https://api.test/media/pages/$imageId"
    private val lesson = HotSoupSeed.lesson.copy(pageImages = listOf(PageImage(imageId, url, 800, 600, "page 1")))
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val calls = mutableListOf<HttpRequestData>()

    /** Hands out `id-1`, and `id-2` once someone asks for a forced refresh; `tokens = 0` is the signed-out app. */
    private class TokenAuth(private val tokens: Int = 1) : AuthProvider {
        var refreshes = 0; private set
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun register(email: String, password: String) = Unit
        override suspend fun signInWithGoogle() = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean): String? {
            if (forceRefresh) refreshes++
            return if (tokens == 0) null else if (forceRefresh) "id-2" else "id-1"
        }
    }

    private fun images(auth: AuthProvider, script: (HttpRequestData) -> HttpStatusCode) = LessonImages(
        auth,
        HttpClient(MockEngine { req ->
            calls += req
            val status = script(req)
            if (status == HttpStatusCode.OK) respond(png, status, headersOf(HttpHeaders.ContentType, "image/png"))
            else respond(ByteArray(0), status)
        }),
    )

    private fun bearers() = calls.map { it.headers[HttpHeaders.Authorization] }

    @Test fun attachesTheBearerTokenToEveryMediaRequest() = runTest {
        val bytes = images(TokenAuth()) { HttpStatusCode.OK }.loaderFor(lesson).load(imageId)
        assertContentEquals(png, bytes)
        assertEquals(1, calls.size)
        assertEquals(url, calls[0].url.toString())
        assertEquals(listOf("Bearer id-1"), bearers())
    }

    @Test fun refreshesOnceAndRetriesOnUnauthorized() = runTest {
        val auth = TokenAuth()
        val bytes = images(auth) { if (calls.size == 1) HttpStatusCode.Unauthorized else HttpStatusCode.OK }.loaderFor(lesson).load(imageId)
        assertContentEquals(png, bytes)
        assertEquals(2, calls.size, "one retry, not more")
        assertEquals(listOf("Bearer id-1", "Bearer id-2"), bearers())
        assertEquals(1, auth.refreshes)
    }

    @Test fun givesUpAfterTheRetryStillFails() = runTest {
        val auth = TokenAuth()
        val bytes = images(auth) { HttpStatusCode.Unauthorized }.loaderFor(lesson).load(imageId)
        assertNull(bytes, "no picture rather than a retry loop")
        assertEquals(2, calls.size)
        assertEquals(1, auth.refreshes)
    }

    @Test fun signedOutMakesNoRequestAtAll() = runTest {
        val bytes = images(TokenAuth(tokens = 0)) { HttpStatusCode.OK }.loaderFor(lesson).load(imageId)
        assertNull(bytes, "the stop falls back to its placeholder")
        assertTrue(calls.isEmpty(), "an anonymous media request would leak another school's page crop")
    }

    @Test fun unknownImageIdAndNoLessonNeverReachTheNetwork() = runTest {
        val loader = images(TokenAuth()) { HttpStatusCode.OK }.loaderFor(lesson)
        assertNull(loader.load("lesson-h:page-9"))
        assertNull(images(TokenAuth()) { HttpStatusCode.OK }.loaderFor(null).load(imageId))
        assertTrue(calls.isEmpty())
    }
}
