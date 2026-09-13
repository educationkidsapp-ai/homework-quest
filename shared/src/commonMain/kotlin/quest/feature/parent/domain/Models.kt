package quest.feature.parent.domain

import kotlinx.datetime.LocalDate
import quest.api.dto.Subject
import quest.feature.practice.domain.Band

data class ChildProfile(
    val id: String,
    val name: String,
    val avatarColor: String,
    val grade: Int,
    val curriculum: String,
    val languages: List<String>,
    val hasPin: Boolean,
)

data class ParentSettings(
    val practiceLength: Int,
    val language: String,       // "en" | "ar"
)

data class SkillReport(
    val skillId: String,
    val name: String,
    val subject: Subject,
    val lessonDate: LocalDate?,
    val band: Band?,
    val accuracyWords: String?,
    val attempts: Long,
    val lastPractised: Long?,
    val requeuedFor: LocalDate?,
)

data class CalendarDay(val date: LocalDate, val subjects: List<Subject>)

interface ParentRepository {
    /** Current parent-mode language code ("en" | "ar"), observable for the theme. */
    val language: kotlinx.coroutines.flow.StateFlow<String>
    suspend fun profile(): ChildProfile
    suspend fun saveProfile(name: String, grade: Int, curriculum: String, avatarColor: String)
    suspend fun setPin(pin: String)
    suspend fun verifyPin(pin: String): Boolean
    suspend fun settings(): ParentSettings
    suspend fun setPracticeLength(length: Int)
    suspend fun setLanguage(code: String)
}
