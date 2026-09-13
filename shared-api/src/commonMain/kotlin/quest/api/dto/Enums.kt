package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Subject { @SerialName("math") MATH, @SerialName("english") ENGLISH }

@Serializable
enum class GenerateMode {
    @SerialName("normal") NORMAL,
    @SerialName("again") AGAIN,
    @SerialName("harder") HARDER,
    @SerialName("easier") EASIER,
}

/** Job status for `GET /lessons/{id}`. `GENERATING` sits between confirm and ready (see README §API). */
@Serializable
enum class LessonStatus {
    @SerialName("uploading") UPLOADING,
    @SerialName("reading") READING,
    @SerialName("needs_confirmation") NEEDS_CONFIRMATION,
    @SerialName("generating") GENERATING,
    @SerialName("ready") READY,
    @SerialName("error") ERROR;

    val isTerminal: Boolean get() = this == NEEDS_CONFIRMATION || this == READY || this == ERROR
}

@Serializable
enum class QuestionType {
    @SerialName("sequence") SEQUENCE,
    @SerialName("count") COUNT,
    @SerialName("compare") COMPARE,
    @SerialName("sound") SOUND,
    @SerialName("word") WORD,
    @SerialName("trace") TRACE,
    @SerialName("readTap") READ_TAP;

    val isNumeric: Boolean get() = this == SEQUENCE || this == COUNT || this == COMPARE
}

@Serializable
data class ApiError(val code: String, val message: String) {
    companion object {
        const val UNREADABLE_FILE = "unreadable_file"
        const val NO_TEACHING_CONTENT = "no_teaching_content"
        const val MODEL_FAILED = "model_failed"
        const val TOO_LARGE = "too_large"
        const val NOT_FOUND = "not_found"
        const val NETWORK = "network"
    }
}
