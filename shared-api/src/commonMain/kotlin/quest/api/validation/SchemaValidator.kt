package quest.api.validation

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import quest.api.Illustrations
import quest.api.dto.Question
import quest.api.dto.QuestionSet
import quest.api.dto.SkillExtraction

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
    companion object { val ok = ValidationResult(emptyList()) }
}

/**
 * Validates model output (and API payloads) against the shared JSON schemas, then applies the rules a
 * schema cannot express (answers are actually correct, ids unique, requested length honoured).
 */
object SchemaValidator {
    val json: Json = Json { ignoreUnknownKeys = false; classDiscriminator = "type"; encodeDefaults = true; explicitNulls = false }

    private val skillSchema by lazy { JsonSchema.fromDefinition(Schemas.SkillExtraction) }
    private val questionSetSchema by lazy { JsonSchema.fromDefinition(Schemas.QuestionSet) }

    fun validateSkillExtractionJson(raw: String): ValidationResult = runCatching {
        val element = json.parseToJsonElement(raw)
        val errors = schemaErrors(skillSchema, element)
        if (errors.isNotEmpty()) return ValidationResult(errors)
        val parsed = json.decodeFromJsonElement(SkillExtraction.serializer(), element)
        validate(parsed)
    }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validate(extraction: SkillExtraction): ValidationResult {
        val errors = mutableListOf<String>()
        val ids = extraction.skills.map { it.id }
        if (ids.size != ids.toSet().size) errors += "skill ids must be unique"
        extraction.skills.forEach { s ->
            if (s.confidence < 0.7 && s.unsure == null) errors += "skill ${s.id}: confidence < 0.7 requires 'unsure'"
            if (s.unsure != null && s.unsure.candidates.distinct().size != 2) errors += "skill ${s.id}: unsure candidates must differ"
        }
        return ValidationResult(errors)
    }

    fun validateQuestionSetJson(raw: String, expectedLength: Int? = null, excludedIds: Set<String> = emptySet()): ValidationResult =
        runCatching {
            val element = json.parseToJsonElement(raw)
            val errors = schemaErrors(questionSetSchema, element)
            if (errors.isNotEmpty()) return ValidationResult(errors)
            val parsed = json.decodeFromJsonElement(QuestionSet.serializer(), element)
            validate(parsed, expectedLength, excludedIds)
        }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validate(set: QuestionSet, expectedLength: Int? = null, excludedIds: Set<String> = emptySet()): ValidationResult {
        val errors = mutableListOf<String>()
        if (wordCount(set.explanation) > 12) errors += "explanation longer than 12 words"
        if (expectedLength != null && set.questions.size != expectedLength) {
            errors += "expected $expectedLength questions, got ${set.questions.size}"
        }
        val ids = set.questions.map { it.id }
        if (ids.size != ids.toSet().size) errors += "question ids must be unique"
        ids.filter { it in excludedIds }.forEach { errors += "question $it was already shown" }
        set.questions.forEach { q -> errors += validate(q).map { "question ${q.id}: $it" } }
        return ValidationResult(errors)
    }

    fun validate(q: Question): List<String> {
        val errors = mutableListOf<String>()
        val optionIds = q.optionIds
        if (optionIds.size != optionIds.toSet().size) errors += "option ids must be unique"
        if (q !is Question.Trace && q.correctOptionId !in optionIds) errors += "correctOptionId is not one of the options"
        when (q) {
            is Question.Sequence -> {
                val missing = q.chips.count { it == null }
                if (missing != 1) errors += "sequence must have exactly one '?' chip"
                val labels = q.options.map { it.label }
                if (labels.size != labels.toSet().size) errors += "duplicate option labels"
                expectedSequenceAnswer(q.chips)?.let { expected ->
                    val chosen = q.options.first { it.id == q.correctOptionId }.label.trim().toIntOrNull()
                    if (chosen != expected) errors += "correct answer should be $expected"
                }
            }
            is Question.Count -> {
                if (!Illustrations.isKnown(q.objectKey)) errors += "unknown illustration ${q.objectKey}"
                val chosen = q.options.firstOrNull { it.id == q.correctOptionId }?.label?.trim()?.toIntOrNull()
                if (chosen != q.total) errors += "correct answer should be ${q.total}"
            }
            is Question.Compare -> {
                val expected = when {
                    q.left < q.right -> "<"
                    q.left > q.right -> ">"
                    else -> "="
                }
                val chosen = q.options.firstOrNull { it.id == q.correctOptionId }?.label
                if (chosen != expected) errors += "correct answer should be $expected"
            }
            is Question.Sound -> if (!Illustrations.isKnown(q.illustrationKey)) errors += "unknown illustration ${q.illustrationKey}"
            is Question.Word -> {
                val labels = q.options.map { it.label.lowercase() }
                if (q.spokenWord.lowercase() !in labels) errors += "spoken word must be one of the options"
                val correct = q.options.first { it.id == q.correctOptionId }.label
                if (!correct.equals(q.spokenWord, ignoreCase = true)) errors += "correct option must be the spoken word"
            }
            is Question.ReadTap -> {
                q.options.forEach { if (!Illustrations.isKnown(it.illustrationKey)) errors += "unknown illustration ${it.illustrationKey}" }
                val correct = q.options.first { it.id == q.correctOptionId }.illustrationKey
                if (!correct.equals(q.word, ignoreCase = true)) errors += "correct picture must match the word"
            }
            is Question.Trace -> if (q.letter.length != 1 || !q.letter[0].isLetter()) errors += "trace letter must be a single letter"
        }
        return errors
    }

    /** Infers the missing number when the visible chips form an arithmetic sequence. */
    internal fun expectedSequenceAnswer(chips: List<Int?>): Int? {
        val idx = chips.indexOf(null)
        if (idx < 0 || chips.count { it == null } != 1) return null
        val known = chips.withIndex().filter { it.value != null }
        if (known.size < 2) return null
        val (i0, v0) = known[0]
        val (i1, v1) = known[1]
        if (i1 == i0) return null
        val diff = (v1!! - v0!!)
        if (diff % (i1 - i0) != 0) return null
        val step = diff / (i1 - i0)
        // every known chip must agree with the step
        if (known.any { (i, v) -> v != v0 + (i - i0) * step }) return null
        return v0 + (idx - i0) * step
    }

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size

    private fun schemaErrors(schema: JsonSchema, element: JsonElement): List<String> {
        val errors = mutableListOf<ValidationError>()
        schema.validate(element) { errors += it }
        return errors.map { "${it.objectPath}: ${it.message}" }
    }
}
