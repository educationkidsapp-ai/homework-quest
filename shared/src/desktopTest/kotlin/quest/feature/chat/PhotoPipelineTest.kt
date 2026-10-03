package quest.feature.chat

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.UploadFile
import quest.core.platform.photoAsJpeg
import quest.feature.chat.data.FileUploadStaging
import quest.feature.chat.data.readBytes
import quest.feature.chat.data.source
import quest.feature.chat.domain.PHOTO_MAX_PX
import quest.feature.chat.domain.PHOTO_QUALITY
import quest.feature.chat.domain.preparePhoto
import quest.feature.content.data.RemoteContentApi
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M7 review: every photo is re-encoded before upload — at most 2560 px, and with **no metadata**, so a phone photo's
 * GPS location never leaves the device; it waits in a cache file rather than in memory; and the upload streams that
 * file with a Content-Length (the server answers 411 without one).
 */
class PhotoPipelineTest {

    /** A 3200 × 2000 JPEG carrying an EXIF block with a GPS IFD — what a phone camera writes. */
    private fun photoWithGps(): ByteArray {
        val surface = Surface.makeRasterN32Premul(3200, 2000)
        surface.canvas.clear(Color.makeRGB(240, 236, 226))
        surface.canvas.drawRect(Rect.makeXYWH(200f, 300f, 1800f, 400f), Paint().apply { color = Color.makeRGB(60, 90, 160) })
        val jpeg = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
        // TIFF (big-endian): IFD0 with one entry, GPSInfo (0x8825) → a GPS IFD with GPSLatitudeRef "N".
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0, 0, 0, 8,
            0, 1, 0x88.toByte(), 0x25, 0, 4, 0, 0, 0, 1, 0, 0, 0, 26, 0, 0, 0, 0,
            0, 1, 0, 1, 0, 2, 0, 0, 0, 2, 'N'.code.toByte(), 0, 0, 0, 0, 0, 0, 0,
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }

    private val exifMarker = "Exif".encodeToByteArray() + byteArrayOf(0, 0)
    private val gpsTag = byteArrayOf(0x88.toByte(), 0x25)

    @Test fun anUploadedPhotoIsDownscaledAndCarriesNoExifOrLocation() {
        val original = photoWithGps()
        assertTrue(original.contains(exifMarker) && original.contains(gpsTag), "the fixture must carry GPS to begin with")

        val upload = assertNotNull(preparePhoto("IMG_1001.JPG", original) { photoAsJpeg(it, PHOTO_MAX_PX, PHOTO_QUALITY) })

        assertEquals("image/jpeg", upload.mimeType)
        assertFalse(upload.bytes.contains(exifMarker), "no EXIF block may reach the server")
        assertFalse(upload.bytes.contains(gpsTag), "no GPS tag may reach the server")
        val sent = Image.makeFromEncoded(upload.bytes)
        assertEquals(PHOTO_MAX_PX, maxOf(sent.width, sent.height))
        assertEquals(1600, sent.height)
    }

    @Test fun aStagedFileIsOnDiskUntilDiscarded() = runBlocking {
        val dir = Files.createTempDirectory("m7-staging").toString()
        val staging = FileUploadStaging(directory = { dir })
        val bytes = ByteArray(4096) { it.toByte() }
        val staged = assertNotNull(staging.stage(UploadFile("hala \"homework\".jpg", "image/jpeg", bytes)))
        assertEquals(4096L, staged.size)
        assertContentEquals(bytes, staged.readBytes())
        assertTrue(Path(staged.path).toString().startsWith(Path(dir, "chat-uploads").toString()))
        staging.discard(staged)
        assertFalse(SystemFileSystem.exists(Path(staged.path)))
    }

    @Test fun theUploadStreamsTheStagedFileWithAContentLength() = runBlocking {
        val dir = Files.createTempDirectory("m7-upload").toString()
        val staged = assertNotNull(FileUploadStaging(directory = { dir }).stage(UploadFile("p.jpg", "image/jpeg", ByteArray(50_000) { 7 })))
        var length: Long? = null
        var path = ""
        val engine = MockEngine { request ->
            length = request.body.contentLength
            path = request.url.encodedPath
            respond(
                """{"id":"a1","name":"p.jpg","type":"image/jpeg","sizeBytes":50000}""", HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val auth = object : AuthProvider {
            override val state = MutableStateFlow<AuthState>(AuthState.SignedIn("u", "u@test"))
            override suspend fun signIn(email: String, password: String) {}
            override suspend fun signOut() {}
            override suspend fun idToken(forceRefresh: Boolean) = "token"
        }
        val ref = RemoteContentApi("http://api.test", auth, HttpClient(engine))
            .uploadChatAttachment("c1", staged.name, staged.contentType, staged.size, { staged.source() }) {}
        assertEquals("a1", ref.id)
        assertEquals("/children/c1/chat/attachments", path)
        assertTrue((length ?: 0) > 50_000, "the multipart body must declare its length; was $length")
    }
}
