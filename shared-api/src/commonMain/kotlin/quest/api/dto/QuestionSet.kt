package quest.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Output of Prompt B and the response of `POST /skills/{id}/generate`. */
@Serializable
data class QuestionSet(
    val skillId: String,
    val mode: GenerateMode,
    val explanation: String,
    val workedExamples: List<WorkedExample>,
    val questions: List<Question>,
    /** Client-side id assigned when the set is stored; the model does not produce it. */
    val id: String? = null,
)

@Serializable
data class WorkedExample(
    val prompt: String,
    val steps: List<String>,
    val answer: String,
)

@Serializable
data class Option(val id: String, val label: String)

@Serializable
data class PictureOption(val id: String, val illustrationKey: String)

@Serializable
data class NumberLine(
    val from: Int,
    val to: Int,
    val step: Int = 1,
    val highlight: List<Int> = emptyList(),
)

@Serializable
sealed interface Question {
    val id: String
    val hint: String
    val type: QuestionType

    /** Option ids a child can tap; empty for [Trace]. */
    val optionIds: List<String>
    val correctOptionId: String?

    @Serializable
    @SerialName("sequence")
    data class Sequence(
        override val id: String,
        override val hint: String,
        val chips: List<Int?>,
        val options: List<Option>,
        override val correctOptionId: String,
        val numberLine: NumberLine,
    ) : Question {
        override val type get() = QuestionType.SEQUENCE
        override val optionIds get() = options.map { it.id }
    }

    @Serializable
    @SerialName("count")
    data class Count(
        override val id: String,
        override val hint: String,
        val objectKey: String,
        val groupSizes: List<Int>,
        val options: List<Option>,
        override val correctOptionId: String,
        val numberLine: NumberLine,
    ) : Question {
        override val type get() = QuestionType.COUNT
        override val optionIds get() = options.map { it.id }
        val total: Int get() = groupSizes.sum()
    }

    @Serializable
    @SerialName("compare")
    data class Compare(
        override val id: String,
        override val hint: String,
        val left: Int,
        val right: Int,
        val options: List<Option>,
        override val correctOptionId: String,
        val numberLine: NumberLine,
    ) : Question {
        override val type get() = QuestionType.COMPARE
        override val optionIds get() = options.map { it.id }
    }

    @Serializable
    @SerialName("sound")
    data class Sound(
        override val id: String,
        override val hint: String,
        val illustrationKey: String,
        val options: List<Option>,
        override val correctOptionId: String,
    ) : Question {
        override val type get() = QuestionType.SOUND
        override val optionIds get() = options.map { it.id }
    }

    @Serializable
    @SerialName("word")
    data class Word(
        override val id: String,
        override val hint: String,
        val spokenWord: String,
        val options: List<Option>,
        override val correctOptionId: String,
    ) : Question {
        override val type get() = QuestionType.WORD
        override val optionIds get() = options.map { it.id }
    }

    @Serializable
    @SerialName("trace")
    data class Trace(
        override val id: String,
        override val hint: String,
        val letter: String,
    ) : Question {
        override val type get() = QuestionType.TRACE
        override val optionIds get() = emptyList<String>()
        override val correctOptionId: String? get() = null
    }

    @Serializable
    @SerialName("readTap")
    data class ReadTap(
        override val id: String,
        override val hint: String,
        val word: String,
        val options: List<PictureOption>,
        override val correctOptionId: String,
    ) : Question {
        override val type get() = QuestionType.READ_TAP
        override val optionIds get() = options.map { it.id }
    }
}
