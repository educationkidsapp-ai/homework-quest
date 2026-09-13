package quest.feature.lesson.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import quest.api.dto.GenerateMode
import quest.api.dto.LessonJob
import quest.api.dto.QuestionSet

interface LessonRepository {
    /** Uploads and records the lesson locally; returns the job in `uploading`. */
    suspend fun create(newLesson: NewLesson): LessonJob

    /** Mirrors every remote status change into the local DB and emits it. */
    fun observe(lessonId: String): Flow<LessonJob>

    suspend fun confirm(lessonId: String, decisions: List<SkillDecision>): LessonJob

    /** Fetches a fresh set, stores it locally, and returns it with its local id. */
    suspend fun generate(skillId: String, mode: GenerateMode): QuestionSet

    suspend fun deleteRemoteFiles(lessonId: String)
    suspend fun deleteAllRemoteFiles(): Int

    suspend fun lesson(id: String): Lesson?
    suspend fun lessonsOn(date: LocalDate): List<Lesson>
    suspend fun allLessons(): List<Lesson>
    suspend fun lessonDates(): List<LocalDate>
    suspend fun skill(id: String): Skill?
    suspend fun skillsForLesson(lessonId: String): List<Skill>
    suspend fun confirmedSkillsFor(date: LocalDate): List<Skill>
    suspend fun allConfirmedSkills(): List<Skill>
    suspend fun requeue(skillId: String, date: LocalDate?)
    suspend fun latestSet(skillId: String): QuestionSet?
    suspend fun setById(setId: String): QuestionSet?
}
