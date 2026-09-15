package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Subject { @SerialName("math") MATH, @SerialName("english") ENGLISH }

@Serializable
enum class Curriculum { @SerialName("american") AMERICAN, @SerialName("british") BRITISH }

/** A (curriculum, grade) pair; six courses exist. */
@Serializable
data class Course(val curriculum: Curriculum, val grade: Int) {
    val key: String get() = "${curriculum.name.lowercase()}/$grade"
    companion object {
        val all: List<Course> = Curriculum.entries.flatMap { c -> (1..3).map { Course(c, it) } }
        fun parse(key: String): Course { val (c, g) = key.split('/'); return Course(Curriculum.valueOf(c.uppercase()), g.toInt()) }
    }
}

@Serializable
enum class SourceKind {
    @SerialName("story") STORY, @SerialName("informational") INFORMATIONAL, @SerialName("math") MATH,
    @SerialName("phonics") PHONICS, @SerialName("vocabulary") VOCABULARY, @SerialName("mixed") MIXED,
}

@Serializable
enum class LessonStatus {
    @SerialName("draft") DRAFT, @SerialName("uploading") UPLOADING, @SerialName("analyzing") ANALYZING,
    @SerialName("needs_review") NEEDS_REVIEW, @SerialName("generating") GENERATING, @SerialName("review") REVIEW,
    @SerialName("published") PUBLISHED, @SerialName("error") ERROR,
    /** A single step was retried and succeeded; the remaining steps wait for "Retry and continue". */
    @SerialName("paused") PAUSED;
    val isTerminal: Boolean get() = this == NEEDS_REVIEW || this == REVIEW || this == PUBLISHED || this == ERROR || this == DRAFT || this == PAUSED
}

@Serializable
enum class IslandKind { @SerialName("lesson") LESSON, @SerialName("review") REVIEW, @SerialName("locked") LOCKED }

@Serializable
enum class IslandState { @SerialName("done") DONE, @SerialName("waiting") WAITING, @SerialName("today") TODAY, @SerialName("locked") LOCKED }

@Serializable
enum class MediaKind { @SerialName("recording") RECORDING, @SerialName("drawing") DRAWING }

@Serializable
data class ApiError(val code: String, val message: String) {
    companion object {
        const val UNREADABLE_FILE = "unreadable_file"; const val NO_TEACHING_CONTENT = "no_teaching_content"; const val MODEL_FAILED = "model_failed"
        const val TOO_LARGE = "too_large"; const val NOT_FOUND = "not_found"; const val BAD_REQUEST = "bad_request"; const val NETWORK = "network"
        const val UNAUTHORIZED = "unauthorized"; const val FORBIDDEN = "forbidden"
    }
}

/** Bilingual text (parent-facing copy is always EN + AR). */
@Serializable
data class Bilingual(val en: String, val ar: String)
