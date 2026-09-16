package quest.feature.school.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import quest.feature.school.domain.SchoolLogoLoader

/**
 * The plain-HTTP logo loader. Bytes are kept per URL for the life of the process: the world-map header draws the logo
 * on every recomposition and a school changes its logo about once a year.
 */
class HttpSchoolLogos(private val client: HttpClient = HttpClient()) : SchoolLogoLoader {
    private val cache = HashMap<String, ByteArray?>()

    override suspend fun load(url: String): ByteArray? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        if (cache.containsKey(url)) return null   // a previous attempt failed; do not hammer the server
        val bytes = runCatching {
            val response = client.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.getOrNull()
        cache[url] = bytes
        return bytes
    }
}
