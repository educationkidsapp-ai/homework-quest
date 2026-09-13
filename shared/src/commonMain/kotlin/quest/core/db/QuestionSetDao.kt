package quest.core.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import quest.api.dto.GenerateMode
import quest.api.dto.NumberLine
import quest.api.dto.Question
import quest.api.dto.QuestionSet
import quest.api.dto.WorkedExample
import quest.core.platform.Ids
import quest.core.platform.Today

/** Persists question sets so play works offline once downloaded (§3 of the dev prompt). */
class QuestionSetDao(private val db: Db) {

    suspend fun save(set: QuestionSet): QuestionSet {
        val id = set.id ?: Ids.random()
        val stored = set.copy(id = id)
        db.write {
            insertQuestionSet(
                id = id, skillId = set.skillId, mode = set.mode.wire(), explanation = set.explanation,
                workedExamplesJson = QuestJson.encodeToString(ListSerializer(WorkedExample.serializer()), set.workedExamples),
                generatedAt = Today.epochMillis(),
            )
            set.questions.forEachIndexed { index, q ->
                insertQuestion(
                    id = q.id, questionSetId = id, type = q.type.name,
                    promptJson = QuestJson.encodeToString(Question.serializer(), q),
                    optionsJson = QuestJson.encodeToString(ListSerializer(String.serializer()), q.optionIds),
                    correctOptionId = q.correctOptionId, hint = q.hint,
                    numberLineJson = numberLineOf(q)?.let { QuestJson.encodeToString(NumberLine.serializer(), it) },
                    illustrationKey = illustrationOf(q), position = index.toLong(),
                )
            }
        }
        return stored
    }

    suspend fun load(setId: String): QuestionSet? = db.read {
        val row = selectQuestionSet(setId).executeAsOneOrNull() ?: return@read null
        val questions = selectQuestionsBySet(setId).executeAsList().map { QuestJson.decodeFromString(Question.serializer(), it.promptJson) }
        QuestionSet(
            id = row.id, skillId = row.skillId, mode = GenerateMode.entries.first { it.wire() == row.mode },
            explanation = row.explanation,
            workedExamples = QuestJson.decodeFromString(ListSerializer(WorkedExample.serializer()), row.workedExamplesJson),
            questions = questions,
        )
    }

    suspend fun latestForSkill(skillId: String): QuestionSet? {
        val id = db.read { selectLatestQuestionSetForSkill(skillId).executeAsOneOrNull()?.id } ?: return null
        return load(id)
    }

    suspend fun allForSkill(skillId: String): List<QuestionSet> {
        val ids = db.read { selectQuestionSetsBySkill(skillId).executeAsList().map { it.id } }
        return ids.mapNotNull { load(it) }
    }

    suspend fun shownQuestionIds(skillId: String): List<String> = db.read { selectQuestionIdsBySkill(skillId).executeAsList() }

    private fun numberLineOf(q: Question): NumberLine? = when (q) {
        is Question.Sequence -> q.numberLine
        is Question.Count -> q.numberLine
        is Question.Compare -> q.numberLine
        else -> null
    }

    private fun illustrationOf(q: Question): String? = when (q) {
        is Question.Sound -> q.illustrationKey
        is Question.Count -> q.objectKey
        is Question.ReadTap -> q.word
        else -> null
    }
}

fun GenerateMode.wire(): String = name.lowercase().replace("_", "")
