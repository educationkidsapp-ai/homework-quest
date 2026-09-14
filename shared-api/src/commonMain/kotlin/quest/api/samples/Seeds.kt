package quest.api.samples

import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary

/** All seeded published lessons (dev prompt §6): Course british/1, 11 and 14 Sep 2026. */
object Seeds {
    val lessons: List<PublishedLesson> = listOf(PhonicsSeed.lesson, MathSeed.lesson, HotSoupSeed.lesson)
    val summaries: List<PublishedLessonSummary> get() = lessons.map { it.summary() }
    fun byId(id: String): PublishedLesson? = lessons.firstOrNull { it.id == id }
}

fun PublishedLesson.summary() = PublishedLessonSummary(id, version, course, subject, date, title, plays.first().stops.size, skills.map { it.id })
