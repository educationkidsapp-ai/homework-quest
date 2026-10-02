package quest.feature.broadcasts.data

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import quest.api.AuthProvider
import quest.api.dto.BroadcastAttachment
import quest.core.platform.safeDocumentName
import quest.core.platform.safeFileName
import quest.core.runCancellable
import quest.feature.broadcasts.domain.AttachmentDocuments
import quest.feature.broadcasts.domain.MAX_DOCUMENT_BYTES
import quest.feature.content.data.bearer

/**
 * M1: `GET /media/attachments/{id}` for a PDF, with the parent's bearer (refreshed once on a 401), **streamed to one
 * file** in [directory] — the document cache the system viewer is later pointed at. Nothing is held in memory beyond
 * one buffer, and nothing is stored twice.
 *
 * The 10 MB cap is enforced twice: a `Content-Length` above it is refused before a byte is read, and the bytes are
 * counted as they arrive, because a server (or something between) can omit or understate the header. On overflow or
 * any failure the partial file is deleted; a download is only ever visible under its final name once it is complete.
 *
 * Everything here runs on [io]: the request, the writes and the directory listing never touch the UI thread.
 */
class AttachmentDocumentStore(
    private val baseUrl: String,
    private val auth: AuthProvider,
    private val client: HttpClient,
    private val directory: () -> String,
    private val maxBytes: Long = MAX_DOCUMENT_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AttachmentDocuments {

    override suspend fun fetch(attachment: BroadcastAttachment): String? = withContext(io) {
        val key = cacheName(attachment) ?: return@withContext null
        val token = auth.idToken() ?: return@withContext null
        // M4 (D13): one folder per attachment, named by its id, so two attachments called "plan.pdf" are two files —
        // and the file inside carries the attachment's own name, which is what the system viewer shows as the title.
        // Both segments are reduced to plain names, so nothing in them can leave the directory.
        val folder = safeFileName(key)
        val name = "$folder/${safeDocumentName(attachment.name ?: key, "pdf")}"
        SystemFileSystem.createDirectories(Path(directory(), folder))
        val target = Path(directory(), name)
        if (SystemFileSystem.exists(target)) return@withContext name
        val url = absolute(attachment.url)
        val saved = when (download(url, token, target)) {
            Download.SAVED -> true
            Download.UNAUTHORISED -> auth.idToken(forceRefresh = true)?.let { download(url, it, target) == Download.SAVED } ?: false
            Download.FAILED -> false
        }
        name.takeIf { saved }
    }

    override suspend fun clear() = withContext(io) {
        val dir = Path(directory())
        runCatching { SystemFileSystem.list(dir).forEach { deleteTree(it) } }
        Unit
    }

    /** A folder per attachment since M4: emptied before it is removed, because a non-empty one cannot be. */
    private fun deleteTree(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) runCatching { SystemFileSystem.list(path).forEach { deleteTree(it) } }
        runCatching { SystemFileSystem.delete(path, mustExist = false) }
    }

    private enum class Download { SAVED, UNAUTHORISED, FAILED }

    private suspend fun download(url: String, token: String, target: Path): Download {
        val part = Path(target.toString() + ".part")
        // The rename and the clean-up are inside the guard too: a full disk or a vanished directory is a failed
        // download, not an exception out of a click handler.
        val outcome = runCancellable {
            client.prepareGet(url) { bearer(token) }.execute { response -> save(response, part) }
                .also { if (it == Download.SAVED) SystemFileSystem.atomicMove(part, target) }
        }.getOrDefault(Download.FAILED)
        if (outcome != Download.SAVED) runCancellable { SystemFileSystem.delete(part, mustExist = false) }
        return outcome
    }

    private suspend fun save(response: HttpResponse, part: Path): Download {
        if (response.status == HttpStatusCode.Unauthorized) return Download.UNAUTHORISED
        if (!response.status.isSuccess()) return Download.FAILED
        if ((response.contentLength() ?: 0L) > maxBytes) return Download.FAILED
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        SystemFileSystem.sink(part).buffered().use { sink ->
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                total += read
                if (total > maxBytes) return Download.FAILED
                sink.write(buffer, 0, read)
            }
        }
        return if (total > 0) Download.SAVED else Download.FAILED
    }

    /** The server answers a root-relative path so a browser on the dashboard needs no base; the app has to add one. */
    private fun absolute(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl.trimEnd('/') + "/" + url.trimStart('/')

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
