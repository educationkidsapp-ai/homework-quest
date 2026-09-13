package quest.feature.lesson

import quest.api.dto.GenerateMode
import quest.api.dto.Question
import quest.api.dto.Subject
import quest.api.validation.SchemaValidator
import quest.feature.lesson.data.FakeGenerator
import quest.feature.lesson.data.SkillSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeGeneratorTest {
    private val math = SkillSpec("counting-by-2s", "Counting by 2s", Subject.MATH, "number line jumps", listOf("2, 4, 6, 8"))
    private val sh = SkillSpec("sh-sound", "The sh sound", Subject.ENGLISH, "word family list", listOf("ship", "shop"))
    private val sight = SkillSpec("sight", "Sight words", Subject.ENGLISH, "look say cover", listOf("the", "and"))

    @Test fun generatedSetsAreSchemaValidForEveryModeAndLength() {
        val gen = FakeGenerator(Random(7))
        for (skill in listOf(math, sh, sight)) for (mode in GenerateMode.entries) for (length in listOf(5, 7, 10)) {
            val set = gen.generate(skill, mode, length, emptySet())
            val json = SchemaValidator.json.encodeToString(quest.api.dto.QuestionSet.serializer(), set)
            val result = SchemaValidator.validateQuestionSetJson(json, expectedLength = length)
            assertTrue(result.isValid, "${skill.id}/$mode/$length: ${result.errors}")
        }
    }

    @Test fun againNeverRepeatsShownQuestions() {
        val gen = FakeGenerator(Random(1))
        val first = gen.generate(math, GenerateMode.NORMAL, 7, emptySet())
        val shown = first.questions.map { it.id }.toSet()
        val again = gen.generate(math, GenerateMode.AGAIN, 7, shown)
        assertEquals(7, again.questions.size)
        assertTrue(again.questions.none { it.id in shown })
    }

    @Test fun mathFollowsTheTeachersStep() {
        val gen = FakeGenerator(Random(3))
        val set = gen.generate(math, GenerateMode.NORMAL, 10, emptySet())
        set.questions.filterIsInstance<Question.Sequence>().forEach { q ->
            val known = q.chips.filterNotNull()
            assertTrue(known.zipWithNext().all { (a, b) -> b - a == 2 || b - a == 4 }, "chips ${q.chips}")
        }
        set.questions.filterIsInstance<Question.Count>().forEach { q -> assertTrue(q.groupSizes.all { it == 2 }) }
    }

    @Test fun harderUsesBiggerNumbers() {
        val gen = FakeGenerator(Random(5))
        val normal = gen.generate(math, GenerateMode.NORMAL, 10, emptySet())
        val harder = gen.generate(math, GenerateMode.HARDER, 10, emptySet())
        fun maxOf(set: quest.api.dto.QuestionSet) = set.questions.mapNotNull { q ->
            when (q) { is Question.Sequence -> q.chips.filterNotNull().max(); is Question.Compare -> maxOf(q.left, q.right); is Question.Count -> q.total; else -> null }
        }.max()
        assertTrue(maxOf(normal) <= 20)
        assertTrue(maxOf(harder) > 20)
    }

    @Test fun englishUsesTheWordFamily() {
        val gen = FakeGenerator(Random(9))
        val set = gen.generate(sh, GenerateMode.NORMAL, 10, emptySet())
        val family = listOf("ship", "sheep", "shop", "shell", "shoe", "fish")
        set.questions.filterIsInstance<Question.Word>().forEach { assertTrue(it.spokenWord in family, it.spokenWord) }
        set.questions.filterIsInstance<Question.ReadTap>().forEach { assertTrue(it.word in family, it.word) }
        assertTrue(set.questions.any { it is Question.Sound })
    }
}
