package quest.feature.parent.domain

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import quest.api.dto.Subject
import quest.api.progress.Band

data class ParentSettings(val language: String)

/** Light, dark, or whatever the device is set to. [key] is what the settings table stores. */
enum class Appearance(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    /** Whether the dark palette applies, given what the device itself is set to. */
    fun isDark(systemDark: Boolean): Boolean = when (this) { SYSTEM -> systemDark; LIGHT -> false; DARK -> true }

    companion object {
        /** An unknown or missing value is [SYSTEM], never a crash. */
        fun of(key: String?): Appearance = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

data class SkillReport(val skillId: String, val name: String, val subject: Subject, val band: Band?, val accuracyWords: String?, val attempts: Int, val lastPractised: Long?)

data class CalendarDay(val date: LocalDate, val subjects: List<Subject>, val lessonIds: List<String>, val doneIds: List<String>) { val done: Boolean get() = lessonIds.isNotEmpty() && doneIds.containsAll(lessonIds) }

interface ParentRepository {
    /** Current parent-mode language code ("en" | "ar"), observable for the theme. */
    val language: StateFlow<String>
    /** The theme choice: [Appearance.SYSTEM] follows the device, the other two pin a palette. Kept on the device. */
    val appearance: StateFlow<Appearance>
    suspend fun setAppearance(appearance: Appearance)
    suspend fun hasPin(): Boolean
    suspend fun setPin(pin: String)
    suspend fun verifyPin(pin: String): Boolean
    suspend fun settings(): ParentSettings
    suspend fun setLanguage(code: String)
}
