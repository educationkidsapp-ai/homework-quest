package quest.feature.broadcasts

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
import quest.core.platform.MediaFiles
import quest.feature.broadcasts.data.AttachmentImageStore
import quest.feature.broadcasts.data.cacheName
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MH3: `GET /media/attachments/{id}` is authenticated, so the weekly plan's image has to be fetched through the app's
 * own client with the parent's bearer — and cached, because the plan a parent looked at on the bus has to still be on
 * the screen in the car park.
 *
 * The cache is the real [MediaFiles], with an id unique to this run and removed afterwards: a fake file store would
 * test the fake, and the thing worth proving is that the second read makes no request at all.
 */
class AttachmentImageStoreTest {
    private val id = "att-test-${kotlin.random.Random.nextInt(1_000_000)}"
    private val image = BroadcastAttachment("/media/attachments/$id", "week-plan.png", id, "image/png")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val calls = mutableListOf<HttpRequestData>()

    private class TokenAuth(private val signedIn: Boolean = true) : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun idToken(forceRefresh: Boolean): String? =
            if (!signedIn) null else if (forceRefresh) "id-2" else "id-1"
    }

    private fun store(auth: AuthProvider = TokenAuth(), status: (HttpRequestData) -> HttpStatusCode = { HttpStatusCode.OK }) =
        AttachmentImageStore(
            "https://api.test",
            auth,
            HttpClient(MockEngine { req ->
                calls += req
                val code = status(req)
                if (code == HttpStatusCode.OK) respond(png, code, headersOf(HttpHeaders.ContentType, "image/png"))
                else respond(ByteArray(0), code)
            }),
        )

    private fun clearCache() = cacheName(image)?.let { MediaFiles.delete(MediaFiles.pathOf(it)) }

    @BeforeTest fun before() { clearCache() }

    @AfterTest fun after() { clearCache() }

    // ---- 1. the download: the root-relative url is resolved against the base, with the bearer on it

    @Test fun downloadsWithTheBearerAgainstTheApiBase() = runTest {
        val bytes = store().load(image)
        assertContentEquals(png, bytes)
        assertEquals(1, calls.size)
        assertEquals("https://api.test/media/attachments/$id", calls[0].url.toString())
        assertEquals("Bearer id-1", calls[0].headers[HttpHeaders.Authorization])
    }

    /** A row whose `url` is already absolute (a pre-MH1 composer typed one) is not prefixed twice. */
    @Test fun anAbsoluteUrlIsLeftAlone() = runTest {
        store().load(image.copy(url = "https://cdn.test/plan.png"))
        assertEquals("https://cdn.test/plan.png", calls.single().url.toString())
    }

    // ---- 2. the cache: written on the way through, read instead of the network next time

    @Test fun theSecondReadComesFromTheCacheWithNoRequest() = runTest {
        assertContentEquals(png, store().load(image))
        assertEquals(1, calls.size)

        // A different store instance, as a later launch would be: the cache is on disk, not in the object.
        calls.clear()
        assertContentEquals(png, store().load(image))
        assertTrue(calls.isEmpty(), "a cached plan is shown offline without asking the server")
    }

    /** Nothing is cached when the fetch failed, so the next attempt is a real retry rather than a cached blank. */
    @Test fun aFailedFetchIsNotCached() = runTest {
        assertNull(store { HttpStatusCode.NotFound }.load(image))
        calls.clear()
        assertContentEquals(png, store().load(image))
        assertEquals(1, calls.size)
    }

    // ---- 3. what is never fetched

    @Test fun anAttachmentWithNoIdIsNeverFetched() = runTest {
        assertNull(store().load(BroadcastAttachment("/media/pages/week-plan.pdf", "week-plan.pdf")))
        assertTrue(calls.isEmpty(), "there is no cache key for it and it is not ours to authenticate")
    }

    @Test fun signedOutMakesNoRequest() = runTest {
        assertNull(store(TokenAuth(signedIn = false)).load(image))
        assertTrue(calls.isEmpty(), "an anonymous media request would be a 401 at best")
    }

    // ---- 4. the cache key reaches a file path, so it may hold nothing a path would misread

    @Test fun theCacheNameIsSafeForAPath() {
        assertEquals("attachment-abc-123_x", cacheName(BroadcastAttachment("/u", null, "abc-123_x")))
        assertEquals("attachment-_____etc", cacheName(BroadcastAttachment("/u", null, "//../etc")))
        assertNull(cacheName(BroadcastAttachment("/u", null, "  ")))
        assertNull(cacheName(BroadcastAttachment("/u", null, null)))
    }
}
