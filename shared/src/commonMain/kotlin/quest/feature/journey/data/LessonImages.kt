package quest.feature.journey.data

import io.ktor.client.HttpClient
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import quest.api.AuthProvider
import quest.api.dto.PublishedLesson
import quest.feature.content.data.getAuthedBytes
import quest.ui.stops.StopImageLoader

/**
 * Resolves a stop's image id through the lesson's `pageImages` list and downloads it from `/media/pages/{id}`.
 *
 * The request carries the parent's Firebase ID token (`Authorization: Bearer …`, refreshed once on a 401) exactly
 * like every other call in [quest.feature.content.data.RemoteContentApi], so the backend can put the media routes behind
 * authentication and scope it by school. Signed out, nothing is requested and the stop draws its placeholder.
 * Decoded bitmaps stay cached per image id in `quest.ui.stops.rememberStopImage`, and the loader itself is
 * remembered per lesson id + version by the stop player.
 */
class LessonImages(private val auth: AuthProvider, private val client: HttpClient = HttpClient()) {
    fun loaderFor(lesson: PublishedLesson?): StopImageLoader {
        val urls = lesson?.pageImages?.associate { it.id to it.url }.orEmpty()
        return StopImageLoader { id ->
            val url = urls[id] ?: return@StopImageLoader null
            val r = client.getAuthedBytes(url, auth) ?: return@StopImageLoader null
            if (r.status.isSuccess()) r.readRawBytes() else null
        }
    }
}
