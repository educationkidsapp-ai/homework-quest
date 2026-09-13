package quest.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import quest.api.dto.QuestionSet
import quest.api.dto.SkillExtraction
import quest.api.samples.Samples
import quest.api.validation.SchemaValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaValidatorTest {
    private val json = SchemaValidator.json

    @Test
    fun sampleSkillExtractionsAreValid() {
        listOf(Samples.skillExtractionMath, Samples.skillExtractionEnglish).forEach {
            val r = SchemaValidator.validateSkillExtractionJson(it)
            assertTrue(r.isValid, r.errors.joinToString())
        }
    }

    @Test
    fun sampleQuestionSetsAreValidAndHaveSevenQuestions() {
        Samples.allQuestionSets.forEach {
            val r = SchemaValidator.validateQuestionSetJson(it, expectedLength = 7)
            assertTrue(r.isValid, r.errors.joinToString())
        }
    }

    @Test
    fun samplesRoundTripThroughDtos() {
        val set = json.decodeFromString(QuestionSet.serializer(), Samples.questionSetCountingBy2s)
        assertEquals(7, set.questions.size)
        val again = json.decodeFromString(QuestionSet.serializer(), json.encodeToString(QuestionSet.serializer(), set))
        assertEquals(set, again)
        val skills = json.decodeFromString(SkillExtraction.serializer(), Samples.skillExtractionMath)
        assertTrue(skills.skills[1].isUnsure)
    }

    @Test
    fun lowConfidenceWithoutUnsureFails() {
        val root = json.parseToJsonElement(Samples.skillExtractionMath).jsonObject
        val skills = root["skills"]!!.jsonArray.map { it.jsonObject }
        val broken = JsonObject(skills[1].filterKeys { it != "unsure" }.plus("confidence" to JsonPrimitive(0.5)))
        val doc = JsonObject(root.plus("skills" to kotlinx.serialization.json.JsonArray(listOf(skills[0], broken))))
        val r = SchemaValidator.validateSkillExtractionJson(doc.toString())
        assertFalse(r.isValid)
    }

    @Test
    fun unknownFieldFails() {
        val broken = Samples.questionSetCountingBy2s.replaceFirst("\"skillId\"", "\"score\": 100, \"skillId\"")
        assertFalse(SchemaValidator.validateQuestionSetJson(broken).isValid)
    }

    @Test
    fun wrongNumericAnswerIsRejected() {
        // c2-q1 correct is "b" (8); flip to "a" (7)
        val broken = Samples.questionSetCountingBy2s.replaceFirst("\"correctOptionId\": \"b\"", "\"correctOptionId\": \"a\"")
        val r = SchemaValidator.validateQuestionSetJson(broken)
        assertFalse(r.isValid)
        assertTrue(r.errors.any { it.contains("should be 8") }, r.errors.joinToString())
    }

    @Test
    fun explanationOverTwelveWordsFails() {
        val broken = Samples.questionSetShSound.replaceFirst(
            "Sh says shhh, like a quiet ship!",
            "one two three four five six seven eight nine ten eleven twelve thirteen",
        )
        assertFalse(SchemaValidator.validateQuestionSetJson(broken).isValid)
    }

    @Test
    fun wrongLengthAndRepeatedIdsAreRejected() {
        assertFalse(SchemaValidator.validateQuestionSetJson(Samples.questionSetCountingBy2s, expectedLength = 5).isValid)
        val r = SchemaValidator.validateQuestionSetJson(Samples.questionSetCountingBy2s, excludedIds = setOf("c2-q3"))
        assertTrue(r.errors.any { it.contains("already shown") })
    }

    @Test
    fun unknownIllustrationFails() {
        val broken = Samples.questionSetShSound.replaceFirst("\"illustrationKey\": \"ship\"", "\"illustrationKey\": \"dragon\"")
        assertFalse(SchemaValidator.validateQuestionSetJson(broken).isValid)
    }

    @Test
    fun sequenceInference() {
        assertEquals(8, SchemaValidator.expectedSequenceAnswer(listOf(2, 4, 6, null)))
        assertEquals(14, SchemaValidator.expectedSequenceAnswer(listOf(10, 12, null, 16)))
        assertEquals(5, SchemaValidator.expectedSequenceAnswer(listOf(null, 10, 15, 20)))
        assertEquals(null, SchemaValidator.expectedSequenceAnswer(listOf(1, 2, 4, null)))
    }

    @Test
    fun illustrationListMatchesSchemaEnum() {
        val schema = json.parseToJsonElement(quest.api.validation.Schemas.QuestionSet).jsonObject
        val enum = schema["\$defs"]!!.jsonObject["illustrationKey"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(Illustrations.keys, enum)
    }
}

private val kotlinx.serialization.json.JsonElement.jsonPrimitive get() = this as JsonPrimitive
