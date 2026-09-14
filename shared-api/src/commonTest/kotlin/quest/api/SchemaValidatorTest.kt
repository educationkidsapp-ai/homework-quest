package quest.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.SourceAnalysis
import quest.api.dto.Stop
import quest.api.map.MapAssembler
import quest.api.samples.HotSoupSeed
import quest.api.samples.MathSeed
import quest.api.samples.PhonicsSeed
import quest.api.samples.Seeds
import quest.api.validation.SchemaValidator
import quest.api.validation.Schemas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SchemaValidatorTest {
    private val json = SchemaValidator.json
    private fun playJson(p: Play) = json.encodeToString(Play.serializer(), p)

    @Test fun everySeededPlayIsValidAgainstTheSchemaAndRules() {
        Seeds.lessons.forEach { lesson ->
            (lesson.plays + lesson.variant).forEach { play ->
                val r = SchemaValidator.validatePlayJson(playJson(play), expectedLevel = play.level)
                assertTrue(r.isValid, "${lesson.id} L${play.level}v${play.variant}: ${r.errors}")
            }
        }
    }

    @Test fun everyStopTypeIsCoveredByTheSeeds() {
        val types = Seeds.lessons.flatMap { l -> (l.plays + l.variant).flatMap { p -> p.stops.flatMap { s -> listOf(s) + ((s as? Stop.ExitTicket)?.questions ?: emptyList()) } } }.map { it.type }.toSet()
        val expected = setOf("readPage", "storyPieces", "wordCards", "move", "explain", "choice", "trueFalse", "sequence", "count", "compare", "sound", "word", "readTap",
            "multiSelect", "selectAll", "match", "order", "trace", "retell", "openAnswer", "writeSentence", "exitTicket")
        assertEquals(expected, types)
    }

    @Test fun analysisAndPanelSamplesAreValid() {
        listOf(HotSoupSeed.analysis, MathSeed.analysis, PhonicsSeed.analysis).forEach {
            val r = SchemaValidator.validateAnalysisJson(json.encodeToString(SourceAnalysis.serializer(), it)); assertTrue(r.isValid, r.errors.toString())
        }
        listOf(HotSoupSeed.parentPanel, MathSeed.parentPanel, PhonicsSeed.parentPanel).forEach {
            val r = SchemaValidator.validatePanelJson(json.encodeToString(ParentPanel.serializer(), it)); assertTrue(r.isValid, r.errors.toString())
        }
    }

    @Test fun playsRoundTripThroughTheSealedType() {
        val p = HotSoupSeed.level1
        val back = json.decodeFromString(Play.serializer(), playJson(p))
        assertEquals(p, back)
        assertTrue(back.stops.last() is Stop.ExitTicket)
    }

    @Test fun unknownFieldsAndWrongAnswersFail() {
        val raw = playJson(MathSeed.level1)
        assertFalse(SchemaValidator.validatePlayJson(raw.replaceFirst("\"level\"", "\"score\":100,\"level\"")).isValid)
        val wrong = MathSeed.level1.copy(stops = MathSeed.level1.stops.map { s -> if (s is Stop.Sequence && s.id == "m1-s1") s.copy(correctOptionId = "b") else s })
        val r = SchemaValidator.validate(wrong)
        assertTrue(r.errors.any { it.contains("should be 8") }, r.errors.toString())
    }

    @Test fun exitTicketRules() {
        val noMulti = HotSoupSeed.level1.copy(stops = HotSoupSeed.level1.stops.dropLast(1) + (HotSoupSeed.level1.stops.last() as Stop.ExitTicket).let { e -> e.copy(questions = e.questions.map { q -> if (q is Stop.MultiSelect) e.questions[0].let { (it as Stop.Choice).copy(id = "dup") } else q }) })
        assertTrue(SchemaValidator.validate(noMulti).errors.any { it.contains("multiSelect") })
        val notLast = HotSoupSeed.level1.copy(stops = HotSoupSeed.level1.stops.reversed())
        assertTrue(SchemaValidator.validate(notLast).errors.any { it.contains("last stop") })
    }

    @Test fun multiSelectAndOrderRules() {
        val ms = (HotSoupSeed.level1.stops.last() as Stop.ExitTicket).questions[1] as Stop.MultiSelect
        assertTrue(SchemaValidator.validate(ms.copy(pick = 3)).any { it.contains("pick") })
        assertTrue(SchemaValidator.validate(ms.copy(correctIds = listOf("v1", "zzz"))).any { it.contains("correctIds") })
        val order = HotSoupSeed.level1.stops.first { it is Stop.Order } as Stop.Order
        assertTrue(SchemaValidator.validate(order.copy(correctOrder = listOf("o1", "o2", "o3"))).any { it.contains("permutation") })
        assertTrue(SchemaValidator.validate(order).isEmpty())
    }

    @Test fun illustrationListMatchesSchemaEnums() {
        listOf(Schemas.Play, Schemas.SourceAnalysis).forEach { schema ->
            val enum = json.parseToJsonElement(schema).jsonObject["\$defs"]!!.jsonObject["illustrationKey"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(Illustrations.keys, enum)
        }
    }

    @Test fun mapAssemblyFollowsTheRule() {
        val child = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
        val today = LocalDate(2026, 9, 14)
        val map = MapAssembler.assemble(child, Seeds.summaries, emptyList(), emptyList(), emptyMap(), LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        val r = SchemaValidator.validateMapJson(json.encodeToString(MapResponse.serializer(), map))
        assertTrue(r.isValid, r.errors.toString())
        val lessons = map.islands.filter { it.kind == IslandKind.LESSON }
        assertEquals(listOf("lesson-sh-sound", "lesson-counting-by-2s", "lesson-hot-soup-1"), lessons.map { it.lessonId })   // date, then math before english
        assertEquals(IslandState.WAITING, lessons[0].state)                     // 11 Sep, not played
        assertEquals(IslandState.TODAY, lessons[1].state)
        assertEquals(listOf(1), lessons[1].levelsUnlocked)
        assertEquals(1, map.islands.count { it.kind == IslandKind.LOCKED })
        assertEquals(LocalDate(2026, 9, 15), map.islands.last { it.kind == IslandKind.LOCKED }.date)

        // an American child sees nothing but the locked island
        val other = MapAssembler.assemble(child.copy(curriculum = Curriculum.AMERICAN), Seeds.summaries, emptyList(), emptyList(), emptyMap(), LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        assertEquals(1, other.islands.size)

        // completion → done + level 2 unlocked; review island appears before today's lessons
        val done = listOf(LessonCompletionInfo("lesson-counting-by-2s", 1, 18, 21, mostStopsTwoStars = true))
        val review = listOf(MapAssembler.ReviewCandidate("sh-sound", "The sh sound", "lesson-sh-sound", "play-sh-1v"))
        val map2 = MapAssembler.assemble(child, Seeds.summaries, done, review, emptyMap(), LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        assertEquals(IslandKind.REVIEW, map2.islands.first().kind)
        val math = map2.islands.first { it.lessonId == "lesson-counting-by-2s" }
        assertEquals(IslandState.DONE, math.state)
        assertEquals(listOf(1, 2), math.levelsUnlocked)
        assertEquals(listOf(1, 2, 3), MapAssembler.unlockedLevels(emptyList(), listOf(3)))
    }

    @Test fun cacheKeysAreDeterministic() {
        val a = CacheKeys.sourceHash(listOf("h2", "h1"), Curriculum.BRITISH, 1, quest.api.dto.Subject.MATH, "notes")
        val b = CacheKeys.sourceHash(listOf("h1", "h2"), Curriculum.BRITISH, 1, quest.api.dto.Subject.MATH, "notes")
        assertEquals(a, b)
        assertFalse(a == CacheKeys.sourceHash(listOf("h1", "h2"), Curriculum.BRITISH, 1, quest.api.dto.Subject.MATH, "other notes"))
        assertEquals("$a|A|${CacheKeys.PROMPT_A_VERSION}", CacheKeys.analysisKey(a))
        assertEquals("$a|B|2|0|0|${CacheKeys.PROMPT_B_VERSION}", CacheKeys.playKey(a, 2, 0, 0))
    }
}
