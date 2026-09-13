package quest.feature.parent.domain

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.feature.lesson.domain.LessonRepository
import quest.feature.practice.domain.Band
import quest.feature.practice.domain.PracticeRepository
import quest.feature.practice.domain.ProgressBands

class VerifyPinUseCase(private val repo: ParentRepository) {
    suspend operator fun invoke(pin: String): Boolean = pin.length == 4 && repo.verifyPin(pin)
}

class SetPinUseCase(private val repo: ParentRepository) {
    suspend operator fun invoke(pin: String) { require(pin.length == 4 && pin.all { it.isDigit() }); repo.setPin(pin) }
}

/** Every confirmed skill with its band — words, never percentages. */
class ProgressReportUseCase(private val lessons: LessonRepository, private val practice: PracticeRepository) {
    suspend operator fun invoke(): List<SkillReport> = lessons.allConfirmedSkills().map { skill ->
        val progress = practice.progress(skill.id)
        SkillReport(
            skillId = skill.id, name = skill.name, subject = skill.subject, lessonDate = skill.lessonDate,
            band = progress.band, accuracyWords = progress.accuracy?.let(ProgressBands::accuracyWords),
            attempts = progress.attempts, lastPractised = progress.lastAnsweredAt, requeuedFor = skill.requeuedFor,
        )
    }
}

/**
 * Weak-skill requeue (dev prompt §5): any skill in "Needs another look" is queued into the next day's
 * practice. Runs after every completed set and on parent-mode entry. Skills that recover are un-queued.
 */
class RequeueWeakSkillsUseCase(private val lessons: LessonRepository, private val practice: PracticeRepository) {
    suspend operator fun invoke(today: LocalDate): List<String> {
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val requeued = mutableListOf<String>()
        lessons.allConfirmedSkills().forEach { skill ->
            val band = practice.progress(skill.id).band
            when {
                band == Band.NEEDS_ANOTHER_LOOK && skill.requeuedFor != tomorrow && skill.lessonDate != tomorrow -> {
                    lessons.requeue(skill.id, tomorrow); requeued += skill.id
                }
                band != Band.NEEDS_ANOTHER_LOOK && skill.requeuedFor != null && skill.requeuedFor > today -> lessons.requeue(skill.id, null)
            }
        }
        return requeued
    }
}

class CalendarUseCase(private val lessons: LessonRepository) {
    suspend fun days(): Map<LocalDate, CalendarDay> = lessons.allLessons()
        .groupBy { it.date }
        .mapValues { (date, ls) -> CalendarDay(date, ls.map { it.subject }.distinct()) }

    suspend fun skillsOn(date: LocalDate) = lessons.confirmedSkillsFor(date)
}
