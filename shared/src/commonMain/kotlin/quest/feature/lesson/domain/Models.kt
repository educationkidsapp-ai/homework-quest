package quest.feature.lesson.domain

import kotlinx.datetime.LocalDate
import quest.api.dto.ApiError
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.api.dto.Unsure

/** A lesson as stored on the device. */
data class Lesson(
    val id: String,
    val date: LocalDate,
    val subject: Subject,
    val status: LessonStatus,
    val sourceFileNames: List<String>,
    val createdAt: Long,
    val error: ApiError? = null,
    val filesDeleted: Boolean = false,
)

/** A skill the teacher taught, as stored on the device (confirmed or not). */
data class Skill(
    val id: String,
    val lessonId: String,
    val name: String,
    val subject: Subject,
    val method: String,
    val confidence: Double,
    val confirmed: Boolean,
    val examples: List<String>,
    val unsure: Unsure? = null,
    val requeuedFor: LocalDate? = null,
    val lessonDate: LocalDate? = null,
)

/** What the parent decided on the Confirm skills screen. */
data class SkillDecision(
    val id: String?,              // null = added manually
    val name: String,
    val subject: Subject,
    val method: String? = null,
    val keep: Boolean = true,
)

data class NewLesson(
    val subject: Subject,
    val date: LocalDate,
    val files: List<quest.api.dto.UploadFile>,
    val typedTask: String? = null,
)
