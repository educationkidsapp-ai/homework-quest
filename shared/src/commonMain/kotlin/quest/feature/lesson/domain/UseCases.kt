package quest.feature.lesson.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import quest.api.dto.GenerateMode
import quest.api.dto.LessonJob
import quest.api.dto.QuestionSet

class AddLessonUseCase(private val repo: LessonRepository) {
    suspend operator fun invoke(newLesson: NewLesson): LessonJob = repo.create(newLesson)
}

class ObserveLessonUseCase(private val repo: LessonRepository) {
    operator fun invoke(lessonId: String): Flow<LessonJob> = repo.observe(lessonId)
}

class ConfirmSkillsUseCase(private val repo: LessonRepository) {
    suspend operator fun invoke(lessonId: String, decisions: List<SkillDecision>): LessonJob {
        require(decisions.any { it.keep }) { "at least one skill must be kept" }
        return repo.confirm(lessonId, decisions)
    }
}

/** "Again" / "Harder" / "Easier": a new set on the same skill, never repeating shown questions. */
class GenerateQuestionSetUseCase(private val repo: LessonRepository) {
    suspend operator fun invoke(skillId: String, mode: GenerateMode): QuestionSet = repo.generate(skillId, mode)
}

/** The child's quest for a date: confirmed skills taught that day plus weak skills re-queued for it. */
class TodaysSkillsUseCase(private val repo: LessonRepository) {
    suspend operator fun invoke(date: LocalDate): List<Skill> = repo.confirmedSkillsFor(date)
}

class DeleteUploadedFilesUseCase(private val repo: LessonRepository) {
    suspend operator fun invoke(): Int = repo.deleteAllRemoteFiles()
}
