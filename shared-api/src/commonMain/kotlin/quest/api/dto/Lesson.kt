package quest.api.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class LessonJob(
    val id: String,
    val status: LessonStatus,
    val subject: Subject,
    val date: LocalDate,
    val skills: List<ExtractedSkill> = emptyList(),
    val questionSets: List<QuestionSet> = emptyList(),
    val error: ApiError? = null,
    val sourceFileNames: List<String> = emptyList(),
)

@Serializable
data class CreateLessonRequest(
    val subject: Subject,
    val grade: Int,
    val curriculum: String,
    val date: LocalDate,
    val practiceLength: Int,
    val typedTask: String? = null,
    val fileNames: List<String> = emptyList(),
)

/** A file to upload; bytes travel as multipart, never as JSON. */
class UploadFile(val fileName: String, val mimeType: String, val bytes: ByteArray) {
    companion object {
        val supportedMimeTypes = setOf(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "image/jpeg", "image/png", "image/webp", "image/heic",
        )
    }
}

@Serializable
data class ConfirmSkillsRequest(
    val skills: List<ConfirmedSkill>,
    val practiceLength: Int,
)

@Serializable
data class ConfirmedSkill(
    val id: String? = null,
    val name: String,
    val subject: Subject,
    val method: String? = null,
)

@Serializable
data class GenerateRequest(
    val mode: GenerateMode,
    val excludeQuestionIds: List<String> = emptyList(),
    val length: Int = 7,
)

@Serializable
data class HealthResponse(val status: String, val version: String)
