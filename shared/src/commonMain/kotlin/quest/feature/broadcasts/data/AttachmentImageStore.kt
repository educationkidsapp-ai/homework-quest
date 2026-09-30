package quest.feature.broadcasts.data

import io.ktor.client.HttpClient
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import quest.api.AuthProvider
import quest.api.dto.BroadcastAttachment
import quest.core.platform.MediaFiles
import quest.core.runCancellable
import quest.feature.broadcasts.domain.AttachmentImages
import quest.feature.content.data.getAuthedBytes

/**
 * MH3: the bytes behind `attachment.url`, which is `GET /media/attachments/{id}` and **authenticated** — the parent's
 * bearer, refreshed once on a 401, exactly as [quest.feature.journey.data.LessonImages] fetches a stop's page. Handing
 * the URL to the platform's viewer would send no token and land on a 401, which is what RM4's "available on the
 * dashboard" line was standing in for.
 *
 * **The cache is the offline story.** A downloaded plan is written to the app's private media directory under the
 * `attachments` row id, and a later read is served from there without a request: the plan a parent looked at on the bus
 * is still on the screen in the car park. The id is a server-minted key that never names different bytes — a re-posted
 * plan is a new row — so there is nothing to invalidate and no ETag to keep.
 *
 * A row written before MH1 carries a URL the composer typed and **no id**: nothing is fetched for it. It is not ours to
 * authenticate, there is no cache key for it, and the card names the file instead.
 */
class AttachmentImageStore(
    private val baseUrl: String,
    private val auth: AuthProvider,
    private val client: HttpClient,
) : AttachmentImages {

    override suspend fun load(attachment: BroadcastAttachment): ByteArray? {
        val name = cacheName(attachment) ?: return null
        MediaFiles.read(MediaFiles.pathOf(name))?.takeIf { it.isNotEmpty() }?.let { return it }
        val response = runCancellable { client.getAuthedBytes(absolute(attachment.url), auth) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        val bytes = runCancellable { response.readRawBytes() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { MediaFiles.save(name, bytes) }
        return bytes
    }

    /** The server answers a root-relative path so a browser on the dashboard needs no base; the app has to add one. */
    private fun absolute(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl.trimEnd('/') + "/" + url.trimStart('/')
}

/**
 * The cache file name for [attachment], or null when it has no id to key on. The id is reduced to characters every
 * platform's file system accepts, because it reaches a path — the same defence the server applies to an upload's name.
 */
fun cacheName(attachment: BroadcastAttachment): String? {
    val id = attachment.id?.takeIf { it.isNotBlank() } ?: return null
    return "attachment-" + id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
