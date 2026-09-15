package quest.feature.journey.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import quest.api.dto.PublishedLesson
import quest.ui.stops.StopImageLoader

/** Resolves a stop's image id through the lesson's `pageImages` list and downloads it (public `/media/pages/{id}` URLs). */
class LessonImages(private val client: HttpClient = HttpClient()) {
    fun loaderFor(lesson: PublishedLesson?): StopImageLoader {
        val urls = lesson?.pageImages?.associate { it.id to it.url }.orEmpty()
        return StopImageLoader { id ->
            val url = urls[id] ?: return@StopImageLoader null
            val r = client.get(url)
            if (r.status.isSuccess()) r.readRawBytes() else null
        }
    }
}
