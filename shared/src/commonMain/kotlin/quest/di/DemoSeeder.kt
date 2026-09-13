package quest.di

import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.NewLesson
import quest.feature.lesson.domain.SkillDecision

/**
 * Fake-API only: on a fresh install, adds today's math and English lessons through the normal upload →
 * confirm path so the child has a quest to play before the parent has used parent mode.
 */
class DemoSeeder(private val lessons: LessonRepository) {
    suspend fun seedIfEmpty(today: LocalDate) {
        if (lessons.allLessons().isNotEmpty()) return
        listOf(Subject.MATH, Subject.ENGLISH).forEach { subject ->
            val job = lessons.create(NewLesson(subject, today, files = emptyList(), typedTask = null))
            val extracted = lessons.observe(job.id).first { it.status.isTerminal }
            if (extracted.status != LessonStatus.NEEDS_CONFIRMATION) return@forEach
            val decisions = extracted.skills.map { s ->
                SkillDecision(id = s.id, name = s.unsure?.candidates?.firstOrNull() ?: s.name, subject = s.subject, method = s.method, keep = true)
            }
            lessons.confirm(job.id, decisions)
            lessons.observe(job.id).first { it.status.isTerminal }
        }
    }
}
