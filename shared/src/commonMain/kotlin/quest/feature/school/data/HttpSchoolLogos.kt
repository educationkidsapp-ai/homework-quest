package quest.feature.school.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.core.runCancellable
import quest.feature.school.domain.SchoolLogoLoader

/**
 * The plain-HTTP logo loader, on the app's one shared [HttpClient]. Bytes are kept per URL for the life of the
 * process: the world-map header draws the logo on every recomposition and a school changes its logo about once a year.
 *
 * A failed URL is cached as a null so a school with a broken logo is not re-fetched on every frame. The map is guarded
 * by a [Mutex] rather than left bare: [load] is called from composition, so two screens drawing the same logo at once
 * would otherwise race on it.
 */
class HttpSchoolLogos(private val client: HttpClient) : SchoolLogoLoader {
    private val mutex = Mutex()
    private val cache = HashMap<String, ByteArray?>()

    override suspend fun load(url: String): ByteArray? {
        if (url.isBlank()) return null
        mutex.withLock { if (cache.containsKey(url)) return cache[url] }
        val bytes = runCancellable {
            val response = client.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull()
        mutex.withLock { cache[url] = bytes }
        return bytes
    }
}
