package quest.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.datetime.LocalDate
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.ReleasedResult
import quest.api.dto.Subject
import quest.api.samples.Seeds
import quest.api.validation.SchemaValidator
import quest.core.json.AppJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * D16: an installed app must keep working when the server adds a field.
 *
 * The server encodes with `encodeDefaults = true`, so a Kotlin default on a new field is written into every response
 * whether or not it is set — a strict app binary therefore throws on the *first* body from a newer server rather than
 * ignoring what it does not know (`docs/runbook.md` "The app and the contract"). Each test below takes a body this
 * build can produce, adds fields no version of this app has heard of, and asserts that [AppJson] reads it while
 * `SchemaValidator.json` — which stays strict, because the schema tests want to hear about undeclared keys — does not.
 */
class ContractToleranceTest {

    /** A field added to the DTO itself, and one added to a nested type (`skills[]`). */
    @Test fun aPublishedLessonWithFieldsThisBuildNeverHeardOfStillDecodes() {
        val lesson = Seeds.lessons.first()
        val body = SchemaValidator.json.encodeToJsonElement(PublishedLesson.serializer(), lesson).jsonObject
        val fromNewerServer = JsonObject(
            body + mapOf(
                "weight" to JsonPrimitive(2),
                "retakePolicy" to JsonPrimitive("once"),
                "skills" to body.getValue("skills").jsonArray.let { skills ->
                    kotlinx.serialization.json.JsonArray(skills.map { JsonObject(it.jsonObject + ("strand" to JsonPrimitive("number"))) })
                },
            ),
        )
        val raw = AppJson.encodeToString(JsonObject.serializer(), fromNewerServer)

        val decoded = AppJson.decodeFromString(PublishedLesson.serializer(), raw)
        assertEquals(lesson.id, decoded.id)
        assertEquals(lesson.title, decoded.title)
        assertEquals(lesson.plays.size, decoded.plays.size)
        assertEquals(lesson.skills.map { it.id }, decoded.skills.map { it.id })

        assertFailsWith<SerializationException>("the validator Json must stay strict") {
            SchemaValidator.json.decodeFromString(PublishedLesson.serializer(), raw)
        }
    }

    /** `ProgressResponse.results[]` is the newest app-facing addition (N4.1); the next one must not break the app. */
    @Test fun aProgressResponseWithFieldsThisBuildNeverHeardOfStillDecodes() {
        val raw = """
            {
              "childId": "c-1",
              "skills": [],
              "weakSkillIds": [],
              "streakDays": 3,
              "lastPlayedDate": "2026-09-21",
              "stickers": ["star"],
              "results": [
                {
                  "lessonId": "l-1", "title": "Counting in 2s", "date": "2026-09-21", "subject": "math",
                  "score": 82, "band": "secure", "comment": "Lovely work on the number line.",
                  "releasedAt": 1758400000000,
                  "markedBy": "maya@test.com", "weight": 1.5
                }
              ],
              "classId": "default:british:1:1a british",
              "sectionName": "1A British"
            }
        """.trimIndent()

        val decoded = AppJson.decodeFromString(ProgressResponse.serializer(), raw)
        assertEquals("c-1", decoded.childId)
        assertEquals(3, decoded.streakDays)
        assertEquals(LocalDate(2026, 9, 21), decoded.lastPlayedDate)
        val result = decoded.results.single()
        assertEquals(ReleasedResult("l-1", "Counting in 2s", LocalDate(2026, 9, 21), Subject.MATH, 82, "secure", "Lovely work on the number line.", 1758400000000L), result)

        assertFailsWith<SerializationException>("the validator Json must stay strict") {
            SchemaValidator.json.decodeFromString(ProgressResponse.serializer(), raw)
        }
    }

    /** The one difference between the two codecs; everything the server relies on has to stay identical. */
    @Test fun appJsonDiffersFromTheValidatorOnlyInTheUnknownKeyPolicy() {
        val a = AppJson.configuration
        val v = SchemaValidator.json.configuration
        assertTrue(a.ignoreUnknownKeys, "the app must ignore unknown keys")
        assertTrue(!v.ignoreUnknownKeys, "the validator must not")
        assertEquals(v.classDiscriminator, a.classDiscriminator)
        assertEquals(v.encodeDefaults, a.encodeDefaults)
        assertEquals(v.explicitNulls, a.explicitNulls)
        assertEquals(v.isLenient, a.isLenient)
        assertEquals(v.coerceInputValues, a.coerceInputValues)
    }

    /** An unknown *code* is not an unknown key: `ApiError` must still surface the server's own message. */
    @Test fun anErrorCodeThisBuildDoesNotKnowKeepsItsMessage() {
        val raw = """{"code":"exam_already_reopened","message":"That exam was already re-opened.","retryAfter":30}"""
        val error = AppJson.decodeFromString(quest.api.dto.ApiError.serializer(), raw)
        assertEquals("exam_already_reopened", error.code)
        assertEquals("That exam was already re-opened.", error.message)
    }
}
