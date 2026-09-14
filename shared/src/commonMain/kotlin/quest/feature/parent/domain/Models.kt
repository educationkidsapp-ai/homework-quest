package quest.feature.parent.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import quest.api.dto.Subject
import quest.api.progress.Band

data class ParentSettings(val language: String)

data class SkillReport(val skillId: String, val name: String, val subject: Subject, val band: Band?, val accuracyWords: String?, val attempts: Int, val lastPractised: Long?)

data class CalendarDay(val date: LocalDate, val subjects: List<Subject>, val lessonIds: List<String>, val doneIds: List<String>) { val done: Boolean get() = lessonIds.isNotEmpty() && doneIds.containsAll(lessonIds) }

interface ParentRepository {
    /** Current parent-mode language code ("en" | "ar"), observable for the theme. */
    val language: StateFlow<String>
    suspend fun hasPin(): Boolean
    suspend fun setPin(pin: String)
    suspend fun verifyPin(pin: String): Boolean
    suspend fun settings(): ParentSettings
    suspend fun setLanguage(code: String)
}
