package quest.feature.parent.domain

import quest.api.dto.Child
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository

/** An exam with answers that have not reached the school: its title and how many. */
data class UndeliveredExam(val lessonId: String, val title: String, val answers: Int)

/**
 * §8: answers of an exam that are still on this device only — given offline, or refused because the window had shut.
 * The parent is told plainly, because only she can do something about it: get the device online, or ask the teacher
 * to re-open the exam (the answers are kept and are delivered then).
 */
class UndeliveredExamAnswersUseCase(private val journey: JourneyRepository, private val lessons: LessonRepository) {
    suspend operator fun invoke(child: Child): List<UndeliveredExam> = journey.pending(child.id).mapNotNull { (lessonId, count) ->
        val lesson = lessons.cached(lessonId)?.takeIf { it.type == "exam" } ?: return@mapNotNull null
        UndeliveredExam(lessonId, lesson.title, count)
    }
}
