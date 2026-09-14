package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class Ingredient(val emoji: String, val name: String)

/** A tappable tile: text, an illustration key, or a cropped page image. At least one must be set. */
@Serializable
data class Tile(val id: String, val label: String? = null, val illustrationKey: String? = null, val pageImageId: String? = null)

@Serializable data class Hotspot(val id: String, val label: String, val x: Float, val y: Float, val w: Float, val h: Float)
@Serializable data class TapTask(val prompt: String, val hotspots: List<Hotspot>, val correctIds: List<String>)
@Serializable data class StoryCard(val piece: String, val definition: String, val answer: String)
@Serializable data class WordCard(val word: String, val meaning: String, val sentence: String, val illustrationKey: String)
@Serializable data class MoveAction(val emoji: String, val text: String)
@Serializable data class WorkedExample(val prompt: String, val steps: List<String>, val answer: String)
@Serializable data class Option(val id: String, val label: String)
@Serializable data class PictureOption(val id: String, val illustrationKey: String)
@Serializable data class NumberLine(val from: Int, val to: Int, val step: Int = 1, val highlight: List<Int> = emptyList())
@Serializable data class MatchPair(val id: String, val left: Tile, val right: Tile)
@Serializable data class OrderItem(val id: String, val text: String, val illustrationKey: String? = null)
@Serializable data class RetellCue(val stage: String, val cue: String, val illustrationKey: String? = null, val pageImageId: String? = null)

/** How a stop is answered, which drives the player and the scoring. */
enum class StopCategory { INFO, SINGLE, MULTI, OPEN, EXIT }

/**
 * One stop on the journey (dev prompt §5). `type` is the JSON discriminator; every stop carries the common
 * fields (title, the sentence Pip speaks, its ingredient, the parent tip) and its own content.
 */
@Serializable
sealed interface Stop {
    val id: String
    val title: String
    val speak: String
    val ingredient: Ingredient
    val parentTip: Bilingual
    val type: String
    val category: StopCategory

    // ---------------------------------------------------------------- information stops
    @Serializable @SerialName("readPage")
    data class ReadPage(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val pageNumber: Int, val sentences: List<String>, val pageImageId: String? = null, val pictureDescription: String? = null,
        val illustrationKey: String? = null, val tapTask: TapTask? = null,
    ) : Stop { override val type get() = "readPage"; override val category get() = StopCategory.INFO }

    @Serializable @SerialName("storyPieces")
    data class StoryPieces(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val cards: List<StoryCard>,
    ) : Stop { override val type get() = "storyPieces"; override val category get() = StopCategory.INFO }

    @Serializable @SerialName("wordCards")
    data class WordCards(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val words: List<WordCard>,
    ) : Stop { override val type get() = "wordCards"; override val category get() = StopCategory.INFO }

    @Serializable @SerialName("move")
    data class Move(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val actions: List<MoveAction>,
    ) : Stop { override val type get() = "move"; override val category get() = StopCategory.INFO }

    @Serializable @SerialName("explain")
    data class Explain(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val skillId: String, val explanation: String, val workedExamples: List<WorkedExample>,
    ) : Stop { override val type get() = "explain"; override val category get() = StopCategory.INFO }

    // ---------------------------------------------------------------- single-answer stops
    sealed interface SingleAnswer : Stop {
        val hint: String
        val correctId: String
        val optionIds: List<String>
        override val category: StopCategory get() = StopCategory.SINGLE
    }

    @Serializable @SerialName("choice")
    data class Choice(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val question: String, val options: List<Tile>, val correctOptionId: String,
    ) : SingleAnswer { override val type get() = "choice"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    @Serializable @SerialName("trueFalse")
    data class TrueFalse(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val statement: String, val answer: Boolean,
    ) : SingleAnswer {
        override val type get() = "trueFalse"; override val correctId get() = if (answer) TRUE_ID else FALSE_ID; override val optionIds get() = listOf(TRUE_ID, FALSE_ID)
        companion object { const val TRUE_ID = "true"; const val FALSE_ID = "false" }
    }

    @Serializable @SerialName("sequence")
    data class Sequence(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val chips: List<Int?>, val options: List<Option>, val correctOptionId: String, val numberLine: NumberLine,
    ) : SingleAnswer { override val type get() = "sequence"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    @Serializable @SerialName("count")
    data class Count(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val objectKey: String, val groupSizes: List<Int>, val options: List<Option>, val correctOptionId: String, val numberLine: NumberLine,
    ) : SingleAnswer { override val type get() = "count"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id }; val total: Int get() = groupSizes.sum() }

    @Serializable @SerialName("compare")
    data class Compare(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val left: Int, val right: Int, val options: List<Option>, val correctOptionId: String, val numberLine: NumberLine,
    ) : SingleAnswer { override val type get() = "compare"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    @Serializable @SerialName("sound")
    data class Sound(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val illustrationKey: String, val options: List<Option>, val correctOptionId: String,
    ) : SingleAnswer { override val type get() = "sound"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    @Serializable @SerialName("word")
    data class Word(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val spokenWord: String, val options: List<Option>, val correctOptionId: String,
    ) : SingleAnswer { override val type get() = "word"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    @Serializable @SerialName("readTap")
    data class ReadTap(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        override val hint: String, val word: String, val options: List<PictureOption>, val correctOptionId: String,
    ) : SingleAnswer { override val type get() = "readTap"; override val correctId get() = correctOptionId; override val optionIds get() = options.map { it.id } }

    // ---------------------------------------------------------------- multi-answer and open stops
    @Serializable @SerialName("multiSelect")
    data class MultiSelect(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val options: List<Tile>, val correctIds: List<String>, val pick: Int,
    ) : Stop { override val type get() = "multiSelect"; override val category get() = StopCategory.MULTI }

    @Serializable @SerialName("selectAll")
    data class SelectAll(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val options: List<Tile>, val correctIds: List<String>,
    ) : Stop { override val type get() = "selectAll"; override val category get() = StopCategory.MULTI }

    @Serializable @SerialName("match")
    data class Match(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val pairs: List<MatchPair>,
    ) : Stop { override val type get() = "match"; override val category get() = StopCategory.MULTI }

    @Serializable @SerialName("order")
    data class Order(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val items: List<OrderItem>, val correctOrder: List<String>,
    ) : Stop { override val type get() = "order"; override val category get() = StopCategory.MULTI }

    @Serializable @SerialName("trace")
    data class Trace(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val text: String, val hint: String,
    ) : Stop { override val type get() = "trace"; override val category get() = StopCategory.MULTI }

    @Serializable @SerialName("retell")
    data class Retell(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val cues: List<RetellCue>, val modelAnswer: String, val record: Boolean = true,
    ) : Stop { override val type get() = "retell"; override val category get() = StopCategory.OPEN }

    @Serializable @SerialName("openAnswer")
    data class OpenAnswer(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val prompt: String, val mode: String, val modelAnswer: String,
    ) : Stop { override val type get() = "openAnswer"; override val category get() = StopCategory.OPEN }

    @Serializable @SerialName("writeSentence")
    data class WriteSentence(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val frame: String, val answer: String, val options: List<String>? = null, val free: Boolean = false,
    ) : Stop { override val type get() = "writeSentence"; override val category get() = if (free) StopCategory.OPEN else StopCategory.SINGLE }

    // ---------------------------------------------------------------- exit ticket
    @Serializable @SerialName("exitTicket")
    data class ExitTicket(
        override val id: String, override val title: String, override val speak: String, override val ingredient: Ingredient, override val parentTip: Bilingual,
        val questions: List<Stop>,
    ) : Stop { override val type get() = "exitTicket"; override val category get() = StopCategory.EXIT }
}

@Serializable
data class Theme(val potName: String, val dishName: String, val potEmoji: String, val servedText: String)

/** One level of a lesson (dev prompt §4). `variant` 0 = main, 1 = the "Again" variant of Level 1. */
@Serializable
data class Play(
    val level: Int,
    val variant: Int = 0,
    val kind: SourceKind,
    val theme: Theme,
    val stops: List<Stop>,
    val id: String? = null,
)

/** Star rules from §5, shared by the app player and the server's usage report. */
object StopScoring {
    fun singleAnswer(attemptNumber: Int): Int = if (attemptNumber <= 1) 3 else 2
    fun byMistakes(mistakes: Int): Int = when { mistakes == 0 -> 3; mistakes <= 2 -> 2; else -> 1 }
    fun byAttempts(attempts: Int): Int = when { attempts <= 1 -> 3; attempts == 2 -> 2; else -> 1 }
    const val INFO = 3
    const val OPEN = 3
}
