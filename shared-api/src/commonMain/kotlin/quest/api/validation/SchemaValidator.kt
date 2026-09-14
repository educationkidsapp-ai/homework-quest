package quest.api.validation

import io.github.optimumcode.json.schema.JsonSchema
import io.github.optimumcode.json.schema.ValidationError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import quest.api.Illustrations
import quest.api.dto.IslandKind
import quest.api.dto.MapResponse
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.SourceAnalysis
import quest.api.dto.Stop
import quest.api.dto.Tile

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
    companion object { val ok = ValidationResult(emptyList()) }
}

/**
 * Validates model output and API payloads against the four shared JSON schemas, then applies the rules a
 * schema cannot express (answers correct, ids unique, exit ticket rules, permutations…).
 */
object SchemaValidator {
    val json: Json = Json { ignoreUnknownKeys = false; classDiscriminator = "type"; encodeDefaults = true; explicitNulls = false }

    private val playSchema by lazy { JsonSchema.fromDefinition(Schemas.Play) }
    private val analysisSchema by lazy { JsonSchema.fromDefinition(Schemas.SourceAnalysis) }
    private val panelSchema by lazy { JsonSchema.fromDefinition(Schemas.ParentPanel) }
    private val mapSchema by lazy { JsonSchema.fromDefinition(Schemas.MapResponse) }

    // ---------------------------------------------------------------- Play
    fun validatePlayJson(raw: String, expectedLevel: Int? = null, excludedIds: Set<String> = emptySet()): ValidationResult = runCatching {
        val element = json.parseToJsonElement(raw)
        val errors = schemaErrors(playSchema, element)
        if (errors.isNotEmpty()) return ValidationResult(errors)
        validate(json.decodeFromJsonElement(Play.serializer(), element), expectedLevel, excludedIds)
    }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validate(play: Play, expectedLevel: Int? = null, excludedIds: Set<String> = emptySet()): ValidationResult {
        val errors = mutableListOf<String>()
        if (expectedLevel != null && play.level != expectedLevel) errors += "expected level $expectedLevel, got ${play.level}"
        val all = play.stops.flatMap { s -> listOf(s) + ((s as? Stop.ExitTicket)?.questions ?: emptyList()) }
        val ids = all.map { it.id }
        if (ids.size != ids.toSet().size) errors += "stop ids must be unique"
        ids.filter { it in excludedIds }.forEach { errors += "stop $it was already used" }
        if (play.stops.lastOrNull() !is Stop.ExitTicket) errors += "the last stop must be an exitTicket"
        play.stops.dropLast(1).filterIsInstance<Stop.ExitTicket>().forEach { errors += "exitTicket ${it.id} must be the last stop" }
        all.forEach { s -> errors += validate(s).map { "stop ${s.id}: $it" } }
        return ValidationResult(errors)
    }

    fun validate(s: Stop): List<String> {
        val e = mutableListOf<String>()
        fun key(k: String?) { if (k != null && !Illustrations.isKnown(k)) e += "unknown illustration $k" }
        fun tiles(ts: List<Tile>) { ts.forEach { key(it.illustrationKey); if (it.label == null && it.illustrationKey == null && it.pageImageId == null) e += "tile ${it.id} is empty" }; if (ts.map { it.id }.toSet().size != ts.size) e += "tile ids must be unique" }
        when (s) {
            is Stop.ReadPage -> { key(s.illustrationKey); s.tapTask?.let { t ->
                val hs = t.hotspots.map { it.id }.toSet(); if (!hs.containsAll(t.correctIds)) e += "tapTask correctIds must be hotspots"; if (t.correctIds.isEmpty()) e += "tapTask needs a correct hotspot" } }
            is Stop.StoryPieces -> if (s.cards.map { it.piece }.toSet().size != 6) e += "storyPieces needs the six distinct pieces"
            is Stop.WordCards -> s.words.forEach { key(it.illustrationKey) }
            is Stop.Move, is Stop.Explain -> Unit
            is Stop.Choice -> { tiles(s.options); if (s.correctOptionId !in s.optionIds) e += "correctOptionId is not an option" }
            is Stop.TrueFalse -> Unit
            is Stop.Sequence -> {
                if (s.chips.count { it == null } != 1) e += "sequence must have exactly one '?' chip"
                if (s.correctOptionId !in s.optionIds) e += "correctOptionId is not an option"
                expectedSequenceAnswer(s.chips)?.let { exp -> if (s.options.firstOrNull { it.id == s.correctOptionId }?.label?.trim()?.toIntOrNull() != exp) e += "correct answer should be $exp" }
            }
            is Stop.Count -> { key(s.objectKey); if (s.options.firstOrNull { it.id == s.correctOptionId }?.label?.trim()?.toIntOrNull() != s.total) e += "correct answer should be ${s.total}" }
            is Stop.Compare -> { val exp = when { s.left < s.right -> "<"; s.left > s.right -> ">"; else -> "=" }; if (s.options.firstOrNull { it.id == s.correctOptionId }?.label != exp) e += "correct answer should be $exp" }
            is Stop.Sound -> { key(s.illustrationKey); if (s.correctOptionId !in s.optionIds) e += "correctOptionId is not an option" }
            is Stop.Word -> { if (s.options.none { it.label.equals(s.spokenWord, true) }) e += "spoken word must be an option"; if (!s.options.first { it.id == s.correctOptionId }.label.equals(s.spokenWord, true)) e += "correct option must be the spoken word" }
            is Stop.ReadTap -> { s.options.forEach { key(it.illustrationKey) }; if (!s.options.first { it.id == s.correctOptionId }.illustrationKey.equals(s.word, true)) e += "correct picture must match the word" }
            is Stop.MultiSelect -> { tiles(s.options); if (!s.options.map { it.id }.containsAll(s.correctIds)) e += "correctIds must be options"; if (s.pick != s.correctIds.size) e += "pick must equal the number of correct ids"; if (s.correctIds.size >= s.options.size) e += "needs at least one wrong option" }
            is Stop.SelectAll -> { tiles(s.options); if (!s.options.map { it.id }.containsAll(s.correctIds)) e += "correctIds must be options"; if (s.correctIds.isEmpty()) e += "needs a correct option" }
            is Stop.Match -> { tiles(s.pairs.map { it.left }); tiles(s.pairs.map { it.right }); if (s.pairs.map { it.id }.toSet().size != s.pairs.size) e += "pair ids must be unique" }
            is Stop.Order -> { s.items.forEach { key(it.illustrationKey) }; if (s.correctOrder.sorted() != s.items.map { it.id }.sorted()) e += "correctOrder must be a permutation of the items" }
            is Stop.Trace -> Unit
            is Stop.Retell -> { s.cues.forEach { key(it.illustrationKey) }; if (s.cues.map { it.stage }.toSet().size != 3) e += "retell needs beginning, middle and end cues" }
            is Stop.OpenAnswer -> Unit
            is Stop.WriteSentence -> { if (!s.frame.contains("___")) e += "frame needs a ___ gap"; s.options?.let { if (s.answer !in it) e += "answer must be one of the options" } }
            is Stop.ExitTicket -> {
                if (s.questions.size != 3) e += "exitTicket needs 3 questions"
                if (s.questions.none { it is Stop.MultiSelect || it is Stop.SelectAll }) e += "exitTicket needs a multiSelect or selectAll"
                if (s.questions.any { it is Stop.ExitTicket || it.category == quest.api.dto.StopCategory.INFO || it.category == quest.api.dto.StopCategory.OPEN }) e += "exitTicket questions must be answerable stops"
            }
        }
        return e
    }

    // ---------------------------------------------------------------- SourceAnalysis
    fun validateAnalysisJson(raw: String): ValidationResult = runCatching {
        val element = json.parseToJsonElement(raw)
        val errors = schemaErrors(analysisSchema, element)
        if (errors.isNotEmpty()) return ValidationResult(errors)
        validate(json.decodeFromJsonElement(SourceAnalysis.serializer(), element))
    }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validate(a: SourceAnalysis): ValidationResult {
        val e = mutableListOf<String>()
        if (a.skills.map { it.id }.toSet().size != a.skills.size) e += "skill ids must be unique"
        a.skills.forEach { s -> if (s.confidence < 0.7 && s.unsure == null) e += "skill ${s.id}: confidence < 0.7 requires 'unsure'" }
        a.pages.flatMap { it.illustrationKeys }.plus(a.vocabulary.map { it.illustrationKey }).filterNot(Illustrations::isKnown).forEach { e += "unknown illustration $it" }
        if (a.objectives.en.size != a.objectives.ar.size) e += "objectives must have the same count in en and ar"
        return ValidationResult(e)
    }

    // ---------------------------------------------------------------- ParentPanel / MapResponse
    fun validatePanelJson(raw: String): ValidationResult = runCatching {
        val element = json.parseToJsonElement(raw)
        val errors = schemaErrors(panelSchema, element)
        if (errors.isNotEmpty()) return ValidationResult(errors)
        val p = json.decodeFromJsonElement(ParentPanel.serializer(), element)
        ValidationResult(if (p.objectives.en.size != p.objectives.ar.size) listOf("objectives must have the same count in en and ar") else emptyList())
    }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validateMapJson(raw: String): ValidationResult = runCatching {
        val element = json.parseToJsonElement(raw)
        val errors = schemaErrors(mapSchema, element)
        if (errors.isNotEmpty()) return ValidationResult(errors)
        validate(json.decodeFromJsonElement(MapResponse.serializer(), element))
    }.getOrElse { ValidationResult(listOf("not valid JSON: ${it.message}")) }

    fun validate(m: MapResponse): ValidationResult {
        val e = mutableListOf<String>()
        if (m.islands.count { it.kind == IslandKind.LOCKED } != 1) e += "exactly one locked island"
        val lessons = m.islands.filter { it.kind == IslandKind.LESSON }
        if (lessons.map { it.date } != lessons.map { it.date }.sorted()) e += "lesson islands must be ordered by date"
        if (m.islands.map { it.id }.toSet().size != m.islands.size) e += "island ids must be unique"
        return ValidationResult(e)
    }

    internal fun expectedSequenceAnswer(chips: List<Int?>): Int? {
        val idx = chips.indexOf(null)
        if (idx < 0 || chips.count { it == null } != 1) return null
        val known = chips.withIndex().filter { it.value != null }
        if (known.size < 2) return null
        val (i0, v0) = known[0]; val (i1, v1) = known[1]
        val diff = v1!! - v0!!
        if (diff % (i1 - i0) != 0) return null
        val step = diff / (i1 - i0)
        if (known.any { (i, v) -> v != v0 + (i - i0) * step }) return null
        return v0 + (idx - i0) * step
    }

    private fun schemaErrors(schema: JsonSchema, element: JsonElement): List<String> {
        val errors = mutableListOf<ValidationError>()
        schema.validate(element) { errors += it }
        return errors.map { "${it.objectPath}: ${it.message}" }.distinct().take(40)
    }
}
