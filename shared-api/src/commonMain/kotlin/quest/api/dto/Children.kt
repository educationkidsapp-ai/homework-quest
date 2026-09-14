package quest.api.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Child(
    val id: String,
    val name: String,
    val avatarColor: String,        // sky | sun | mint | lavender (Pip in four colours)
    val curriculum: Curriculum,
    val grade: Int,
    val languages: List<String> = listOf("en"),
) { val course: Course get() = Course(curriculum, grade) }

@Serializable
data class CreateChildRequest(val name: String, val avatarColor: String, val curriculum: Curriculum, val grade: Int, val languages: List<String> = listOf("en"))

@Serializable
data class UpdateChildRequest(val name: String? = null, val avatarColor: String? = null, val curriculum: Curriculum? = null, val grade: Int? = null, val languages: List<String>? = null)

/** One answered stop, uploaded in batches. `answerJson` is the raw answer payload (option ids, order, hotspot ids…). */
@Serializable
data class AttemptUpload(
    val id: String,
    val stopId: String,
    val lessonId: String,
    val level: Int,
    val answerJson: String,
    val correct: Boolean,
    val attemptNumber: Int,
    val mistakes: Int,
    val stars: Int,
    val answeredAt: Long,
)

@Serializable data class AttemptAck(val accepted: Int)
@Serializable data class MediaRef(val id: String, val url: String, val kind: MediaKind)

@Serializable
data class ProgressResponse(
    val childId: String,
    val skills: List<SkillProgress>,
    val weakSkillIds: List<String>,
    val streakDays: Int,
    val lastPlayedDate: LocalDate?,
    val stickers: List<String>,
)

@Serializable
data class SkillProgress(val skillId: String, val name: String, val subject: Subject, val band: String?, val firstTryAccuracyWords: String?, val attempts: Int, val lastPractised: Long?)
