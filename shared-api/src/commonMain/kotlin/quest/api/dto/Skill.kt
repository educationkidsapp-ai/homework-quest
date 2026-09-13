package quest.api.dto

import kotlinx.serialization.Serializable

/** Output of Prompt A, validated against `SkillExtraction.schema.json`. */
@Serializable
data class SkillExtraction(
    val subject: Subject,
    val skills: List<ExtractedSkill>,
)

@Serializable
data class ExtractedSkill(
    val id: String,
    val name: String,
    val subject: Subject,
    val method: String,
    val examples: List<String>,
    val slideNumbers: List<Int>,
    val confidence: Double,
    val unsure: Unsure? = null,
) {
    val isUnsure: Boolean get() = unsure != null
}

@Serializable
data class Unsure(
    val candidates: List<String>,
    val question: String,
)
