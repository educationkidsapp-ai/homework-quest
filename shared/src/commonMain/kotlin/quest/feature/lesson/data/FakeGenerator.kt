package quest.feature.lesson.data

import quest.api.Illustrations
import quest.api.dto.GenerateMode
import quest.api.dto.NumberLine
import quest.api.dto.Option
import quest.api.dto.PictureOption
import quest.api.dto.Question
import quest.api.dto.QuestionSet
import quest.api.dto.Subject
import quest.api.dto.WorkedExample
import kotlin.random.Random

/** The skill facts the generator needs (mirrors what Prompt B receives). */
data class SkillSpec(val id: String, val name: String, val subject: Subject, val method: String, val examples: List<String>)

/**
 * Deterministic, schema-valid question generation used by [FakeLessonApi]. It follows the same rules
 * Prompt B is given: the teacher's method (number line jumps, word family list), plausible distractors,
 * never repeating ids, harder = bigger numbers / more choices, easier = smaller numbers / fewer choices.
 */
class FakeGenerator(private val random: Random = Random.Default) {

    fun generate(skill: SkillSpec, mode: GenerateMode, length: Int, excluded: Set<String>, nonce: String = random.nextInt(1000, 9999).toString()): QuestionSet {
        val idPrefix = "${skill.id}-${mode.name.lowercase()}-$nonce"
        val questions = when (skill.subject) {
            Subject.MATH -> mathQuestions(skill, mode, length, idPrefix)
            Subject.ENGLISH -> englishQuestions(skill, mode, length, idPrefix)
        }.filter { it.id !in excluded }
        return QuestionSet(
            skillId = skill.id,
            mode = mode,
            explanation = explanation(skill),
            workedExamples = workedExamples(skill),
            questions = questions.take(length),
        )
    }

    // ---------------------------------------------------------------- math
    private fun stepOf(skill: SkillSpec): Int {
        val m = Regex("(\\d+)s").find(skill.name.lowercase() + " " + skill.examples.joinToString(" "))
        return m?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 1
    }

    private fun mathQuestions(skill: SkillSpec, mode: GenerateMode, length: Int, prefix: String): List<Question> {
        val step = stepOf(skill)
        val max = when (mode) {
            GenerateMode.EASIER -> 10
            GenerateMode.HARDER -> 30
            else -> 20
        }.coerceAtLeast(step * 5)
        val optionCount = if (mode == GenerateMode.EASIER) 2 else 3
        val cycle = listOf("sequence", "count", "sequence", "compare", "count", "sequence", "compare", "sequence", "count", "compare")
        return (0 until length).map { i ->
            val id = "$prefix-q${i + 1}"
            when (cycle[i % cycle.size]) {
                "sequence" -> sequence(id, step, max, optionCount, allowFirstMissing = mode == GenerateMode.HARDER)
                "count" -> count(id, step, mode, optionCount)
                else -> compare(id, step, max)
            }
        }
    }

    private fun sequence(id: String, step: Int, max: Int, optionCount: Int, allowFirstMissing: Boolean): Question.Sequence {
        val len = 4
        val startMultiples = (0..((max - step * (len - 1)) / step)).toList()
        val start = startMultiples.random(random) * step
        val values = List(len) { start + it * step }
        val missing = if (allowFirstMissing) random.nextInt(len) else random.nextInt(1, len)
        val answer = values[missing]
        val chips = values.mapIndexed { i, v -> if (i == missing) null else v }
        val options = options(answer, listOf(answer + 1, answer - 1, answer + step, answer - step, answer + 2), optionCount)
        val line = numberLine(values.first(), values.last(), step, values)
        return Question.Sequence(id, hint = "Start at ${values[(missing - 1).coerceAtLeast(0)]} and jump $step.", chips = chips, options = options.first, correctOptionId = options.second, numberLine = line)
    }

    private val countObjects = listOf("shoe", "sock", "apple", "star", "ball", "fish", "bee")

    private fun count(id: String, step: Int, mode: GenerateMode, optionCount: Int): Question.Count {
        val group = step.coerceIn(1, 5)
        val groups = when (mode) {
            GenerateMode.EASIER -> random.nextInt(1, 4)
            GenerateMode.HARDER -> random.nextInt(3, 7)
            else -> random.nextInt(2, 5)
        }
        val sizes = List(groups) { group }
        val total = sizes.sum()
        val key = countObjects.random(random)
        val options = options(total, listOf(total + group, total - group, total + 1, total - 1, groups), optionCount)
        val line = numberLine(0, total + group, 1, (1..groups).map { it * group })
        val hint = if (group == 1) "Touch each $key and count." else "Count in ${group}s: ${(1..groups).joinToString(", ") { (it * group).toString() }}."
        return Question.Count(id, hint, key, sizes, options.first, options.second, line)
    }

    private fun compare(id: String, step: Int, max: Int): Question.Compare {
        val a = random.nextInt(0, max / step + 1) * step
        var b = random.nextInt(0, max / step + 1) * step
        if (random.nextInt(5) == 0) b = a
        val correct = when { a < b -> "<"; a > b -> ">"; else -> "=" }
        val options = listOf(Option("a", "<"), Option("b", ">"), Option("c", "="))
        val line = numberLine(minOf(a, b), maxOf(a, b), if (max > 20) step else 1, listOf(a, b))
        return Question.Compare(id, "Find $a and $b on the number line. Which is further along?", a, b, options, options.first { it.label == correct }.id, line)
    }

    private fun options(correct: Int, distractors: List<Int>, count: Int): Pair<List<Option>, String> {
        val values = mutableListOf(correct)
        for (d in distractors.shuffled(random)) if (values.size < count && d >= 0 && d !in values) values += d
        while (values.size < count) values += values.max() + 1
        val shuffled = values.shuffled(random)
        val ids = listOf("a", "b", "c", "d")
        val options = shuffled.mapIndexed { i, v -> Option(ids[i], v.toString()) }
        return options to options.first { it.label == correct.toString() }.id
    }

    private fun numberLine(lo: Int, hi: Int, step: Int, highlight: List<Int>): NumberLine {
        var from = ((lo - step) / step) * step
        if (from < 0) from = 0
        var to = hi + step
        // keep at most ~15 ticks
        var s = step
        while ((to - from) / s > 15) s *= 2
        return NumberLine(from, to, s, highlight.distinct().sorted())
    }

    // -------------------------------------------------------------- english
    private val families = mapOf(
        "sh" to listOf("ship", "sheep", "shop", "shell", "shoe", "fish"),
        "ch" to listOf("chair", "cheese", "chick", "chips"),
        "th" to listOf("thumb", "three", "bath", "moth"),
    )
    private val sightWords = listOf("the", "and", "is", "in", "it", "he", "she", "we", "to", "a", "go", "my")
    private val nouns = Illustrations.keys
    private val lookAlikes = mapOf(
        "ship" to listOf("chip", "shop"), "sheep" to listOf("cheap", "sleep"), "shop" to listOf("chop", "stop"), "shell" to listOf("sell", "smell"),
        "shoe" to listOf("she", "show"), "fish" to listOf("fist", "dish"), "chair" to listOf("share", "cheer"), "cheese" to listOf("these", "chase"),
        "chick" to listOf("thick", "click"), "chips" to listOf("ships", "chops"), "thumb" to listOf("thump", "dumb"), "three" to listOf("tree", "free"),
        "bath" to listOf("bat", "path"), "moth" to listOf("mouth", "math"),
    )

    private fun familyOf(skill: SkillSpec): String? {
        val text = (skill.name + " " + skill.examples.joinToString(" ")).lowercase()
        return families.keys.firstOrNull { f -> Regex("\\b$f\\b|\\b${f}[a-z]").containsMatchIn(text) || families[f]!!.any { it in text } }
    }

    private fun englishQuestions(skill: SkillSpec, mode: GenerateMode, length: Int, prefix: String): List<Question> {
        val family = familyOf(skill)
        val optionCount = if (mode == GenerateMode.EASIER) 2 else 3
        val cycle = if (family != null) listOf("sound", "word", "readTap", "sound", "trace", "word", "readTap", "sound", "word", "trace")
        else listOf("word", "word", "trace", "word", "readTap", "trace", "word", "readTap", "word", "trace")
        return (0 until length).map { i ->
            val id = "$prefix-q${i + 1}"
            when (cycle[i % cycle.size]) {
                "sound" -> sound(id, family!!, optionCount, mode)
                "word" -> word(id, family, skill)
                "readTap" -> readTap(id, family)
                else -> trace(id, family, skill, i)
            }
        }
    }

    private fun sound(id: String, family: String, optionCount: Int, mode: GenerateMode): Question.Sound {
        // Mostly the family itself; sometimes a contrast picture so the child listens rather than guesses.
        val contrast = mode != GenerateMode.EASIER && random.nextInt(3) == 0
        val pickFamily = if (contrast) (families.keys - family).random(random) else family
        val key = families[pickFamily]!!.random(random)
        val others = (families.keys - pickFamily).shuffled(random).take(optionCount - 1)
        val labels = (listOf(pickFamily) + others).shuffled(random)
        val options = labels.mapIndexed { i, l -> Option(listOf("a", "b", "c")[i], l) }
        val hint = "Say $key slowly. Listen to the first sound: $pickFamily."
        return Question.Sound(id, hint, key, options, options.first { it.label == pickFamily }.id)
    }

    private fun word(id: String, family: String?, skill: SkillSpec): Question.Word {
        val pool = if (family != null) families[family]!! else sightWords
        val target = pool.random(random)
        val distractors = (lookAlikes[target] ?: (pool - target).shuffled(random)).take(2).toMutableList()
        while (distractors.size < 2) distractors += (sightWords - target - distractors).random(random)
        val labels = (listOf(target) + distractors).shuffled(random)
        val options = labels.mapIndexed { i, l -> Option(listOf("a", "b", "c")[i], l) }
        val hint = if (family != null) "Listen for the $family at the start." else "Look at the first letter: ${target.first()}."
        return Question.Word(id, hint, target, options, options.first { it.label == target }.id)
    }

    private fun readTap(id: String, family: String?): Question.ReadTap {
        val pool = if (family != null) families[family]!! else listOf("sun", "cat", "dog", "hat", "bed", "cup", "pen", "pig", "bus", "fox", "bee", "car")
        val target = pool.random(random)
        val others = (nouns - target).shuffled(random).take(2)
        val keys = (listOf(target) + others).shuffled(random)
        val options = keys.mapIndexed { i, k -> PictureOption(listOf("a", "b", "c")[i], k) }
        val hint = "${target.map { it }.joinToString("-")}. Say it slowly."
        return Question.ReadTap(id, hint, target, options, options.first { it.illustrationKey == target }.id)
    }

    private val traceHints = mapOf(
        'S' to "Start at the top and curve like a snake.", 's' to "A small snake: curve, then curve back.",
        'C' to "Start at the top and go round like a moon.", 'c' to "A little moon: round and stop.",
        'T' to "A line down, then a hat on top.", 't' to "A line down, then a short line across.",
        'H' to "Two tall lines and a bridge in the middle.", 'h' to "A tall line, then a bump.",
        'A' to "Up the mountain, down the mountain, then a bridge.", 'a' to "Round like a ball, then a tail.",
        'i' to "A straight line down, then a dot on top.", 'e' to "A short line across, then round.",
    )

    private fun trace(id: String, family: String?, skill: SkillSpec, i: Int): Question.Trace {
        val letters = if (family != null) family.toCharArray().map { if (i % 2 == 0) it.uppercaseChar() else it } else listOf('a', 'i', 'e', 't', 'h', 's')
        val letter = letters.random(random)
        return Question.Trace(id, traceHints[letter] ?: "Start at the top and follow the dots.", letter.toString())
    }

    // ------------------------------------------------------------- copy
    private fun explanation(skill: SkillSpec): String = when (skill.subject) {
        Subject.MATH -> {
            val step = stepOf(skill)
            if (step > 1) "Counting by ${step}s means we jump $step each time!" else "Let's count carefully, one at a time!"
        }
        Subject.ENGLISH -> familyOf(skill)?.let { "$it makes one sound. Listen: ${families[it]!!.first()}!" } ?: "Sight words are words we know just by looking!"
    }

    private fun workedExamples(skill: SkillSpec): List<WorkedExample> = when (skill.subject) {
        Subject.MATH -> {
            val s = stepOf(skill)
            listOf(
                WorkedExample("${s}, ${2 * s}, ${3 * s}, ?", listOf("Start at ${3 * s}", "Jump $s on the number line", "Land on ${4 * s}"), "${4 * s}"),
                WorkedExample("2 groups of $s", listOf("One group is $s", "Two groups: $s, ${2 * s}"), "${2 * s}"),
            )
        }
        Subject.ENGLISH -> familyOf(skill)?.let { f ->
            families[f]!!.take(2).map { w -> WorkedExample(w, listOf("Say $f", "Say ${w.removePrefix(f)}", "Blend: $w"), "$f-${w.removePrefix(f)}") }
        } ?: listOf(
            WorkedExample("the", listOf("Look at the word", "Say it: the", "Cover it and say it again"), "the"),
            WorkedExample("and", listOf("Look at the word", "Say it: and", "Find it in a sentence"), "and"),
        )
    }
}
