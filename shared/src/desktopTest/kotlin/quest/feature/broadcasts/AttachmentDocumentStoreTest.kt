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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.BroadcastAttachment
import quest.feature.broadcasts.data.AttachmentDocumentStore
import quest.feature.broadcasts.domain.AttachmentDocuments
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.domain.SignOutUseCase
import quest.api.dto.Child
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1: a weekly plan PDF goes from `/media/attachments/{id}` straight into one file of the document cache — streamed,
 * capped at the limit, never left half-written, and gone when the parent signs out.
 */
class AttachmentDocumentStoreTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "hq-doc-test-${System.nanoTime()}").apply { mkdirs() }
    private val pdf = BroadcastAttachment("/media/attachments/att-1", "Grade 1 plan.pdf", "att-1", "application/pdf")
    private val calls = mutableListOf<HttpRequestData>()

    @AfterTest fun tearDown() { dir.deleteRecursively() }

    private class TokenAuth(private val signedIn: Boolean = true) : AuthProvider {
        var signedOut = 0
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedOut)
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun signOut() { signedOut++ }
        override suspend fun idToken(forceRefresh: Boolean): String? = if (!signedIn) null else if (forceRefresh) "id-2" else "id-1"
    }

    private fun kotlinx.coroutines.test.TestScope.store(
        body: ByteArray,
        maxBytes: Long = 1024,
        auth: AuthProvider = TokenAuth(),
        declareLength: Boolean = true,
        status: (HttpRequestData) -> HttpStatusCode = { HttpStatusCode.OK },
    ) = AttachmentDocumentStore(
        "https://api.test", auth,
        HttpClient(MockEngine { req ->
            calls += req
            val code = status(req)
            val headers = if (declareLength) headersOf(HttpHeaders.ContentLength, "${body.size}") else headersOf()
            if (code == HttpStatusCode.OK) respond(io.ktor.utils.io.ByteReadChannel(body), code, headers) else respond(ByteArray(0), code)
        }),
        directory = { dir.absolutePath }, maxBytes = maxBytes, io = StandardTestDispatcher(testScheduler),
    )

    private fun files() = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.toList().sorted()

    @Test fun aPdfIsStreamedToOneFileUnderItsOwnName() = runTest {
        val bytes = ByteArray(700) { (it % 251).toByte() }
        val name = store(bytes).fetch(pdf)
        // M4 (D13): the file carries the attachment's own name — the viewer's title — inside a folder named by its id.
        assertEquals("attachment-att-1/Grade 1 plan.pdf", name)
        assertEquals(listOf(name), files(), "one copy, and no .part left behind")
        assertContentEquals(bytes, File(dir, name!!).readBytes())
        assertEquals("Bearer id-1", calls.single().headers[HttpHeaders.Authorization])
    }

    @Test fun aSecondOpenIsServedFromTheCache() = runTest {
        val store = store(ByteArray(10) { 1 })
        assertNotNull(store.fetch(pdf)); assertNotNull(store.fetch(pdf))
        assertEquals(1, calls.size)
    }

    @Test fun aDeclaredLengthOverTheCapIsRefusedBeforeAByteIsWritten() = runTest {
        assertNull(store(ByteArray(2048), maxBytes = 1024).fetch(pdf))
        assertTrue(files().isEmpty())
    }

    @Test fun anUndeclaredBodyOverTheCapIsAbandonedAndDeleted() = runTest {
        // No Content-Length to trust: the bytes are counted as they arrive and the partial file is removed.
        assertNull(store(ByteArray(200_000), maxBytes = 70_000, declareLength = false).fetch(pdf))
        assertTrue(files().isEmpty())
    }

    @Test fun anExpiredTokenIsRefreshedOnce() = runTest {
        val store = store(ByteArray(10) { 1 }, status = { if (it.headers[HttpHeaders.Authorization] == "Bearer id-1") HttpStatusCode.Unauthorized else HttpStatusCode.OK })
        assertNotNull(store.fetch(pdf))
        assertEquals(listOf("Bearer id-1", "Bearer id-2"), calls.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test fun aFailedDownloadLeavesNothingBehind() = runTest {
        assertNull(store(ByteArray(10), status = { HttpStatusCode.InternalServerError }).fetch(pdf))
        assertTrue(files().isEmpty())
    }

    @Test fun nothingIsFetchedSignedOutOrWithoutAnId() = runTest {
        assertNull(store(ByteArray(10), auth = TokenAuth(signedIn = false)).fetch(pdf))
        assertNull(store(ByteArray(10)).fetch(pdf.copy(id = null)))
        assertTrue(calls.isEmpty())
    }

    @Test fun aHostileNameCannotLeaveTheCacheDirectory() = runTest {
        val name = store(ByteArray(10) { 1 }).fetch(pdf.copy(name = "../../etc/passwd"))
        assertEquals("attachment-att-1/passwd.pdf", name)
        assertEquals(listOf(name), files())
    }

    // ---- sign-out clears what the parent downloaded

    private class Children : ChildrenRepository {
        var cleared = 0
        override val currentChild: StateFlow<Child?> = MutableStateFlow(null)
        override suspend fun refresh(): List<Child> = emptyList()
        override suspend fun children(): List<Child> = emptyList()
        override suspend fun select(id: String) {}
        override suspend fun clear() { cleared++ }
    }

    @Test fun signingOutDeletesTheCachedDocuments() = runTest {
        val auth = TokenAuth()
        val store: AttachmentDocuments = store(ByteArray(10) { 1 }, auth = auth)
        assertNotNull(store.fetch(pdf))
        assertEquals(1, files().size)

        val children = Children()
        SignOutUseCase(auth, children, store)()

        assertTrue(files().isEmpty(), "a weekly plan must not outlive the session that downloaded it")
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "nor the folder it was kept in")
        assertEquals(1, auth.signedOut)
        assertEquals(1, children.cleared)
    }
}
