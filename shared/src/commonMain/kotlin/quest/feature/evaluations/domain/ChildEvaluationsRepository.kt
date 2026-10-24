package quest.feature.evaluations.domain

import quest.api.dto.EvaluationCriterion
import quest.api.dto.EvaluationWord
import quest.api.dto.ParentEvaluation

/**
 * Feature 33, the parent's half: what her child's teachers wrote about her — `GET /children/{id}/evaluations`.
 *
 * There is nothing to write here. A parent reads an evaluation and answers it in her ordinary one-to-one thread with
 * the teacher ("Reply privately"), so this repository only reads, and it reads **words**: the server has already
 * dropped every draft, every state and every number before the row leaves it, which is why nothing in this package
 * knows there was ever a rating at all.
 */
interface ChildEvaluationsRepository {
    suspend fun evaluations(childId: String): List<ParentEvaluation>
}

/** The four criteria, in the order the parent reads them (the spec's order, §"The teacher"). */
enum class EvaluationCriterionKey(val key: String) {
    PARTICIPATION("participation"),
    EFFORT("effort"),
    BEHAVIOUR("behaviour"),
    UNDERSTANDING("understanding"),
}

/** The four points of the scale, best first — the order a chip's colour is chosen by, never a number. */
val EVALUATION_WORDS = listOf(
    EvaluationWord.EXCELLENT, EvaluationWord.GOOD, EvaluationWord.DEVELOPING, EvaluationWord.NEEDS_SUPPORT,
)

/**
 * One round as the page reads it: its name in both languages and the evaluations in it, the server's own order kept
 * (by subject). [id] is [ParentEvaluation.roundId], so two rounds that happen to share a name stay apart.
 */
data class EvaluationRound(
    val id: String,
    val name: String,
    val nameAr: String,
    val rows: List<ParentEvaluation>,
)

/**
 * The page's sections. The server sends the rows newest round first and then by subject, and that order is kept
 * rather than guessed at again here: a round has no date in the parent's shape (there is no deadline, no state and no
 * release to show her), so the app has nothing better to sort by than the answer it was given.
 */
fun rounds(rows: List<ParentEvaluation>): List<EvaluationRound> =
    rows.groupBy { it.roundId }.map { (id, inRound) ->
        EvaluationRound(id = id, name = inRound.first().roundName, nameAr = inRound.first().roundNameAr, rows = inRound)
    }

/**
 * The criteria of one evaluation in the spec's order, whatever order they arrived in, with a key this build does not
 * know kept at the end rather than dropped — the same rule an unknown score band is read by.
 */
fun orderedCriteria(criteria: List<EvaluationCriterion>): List<EvaluationCriterion> {
    val known = EvaluationCriterionKey.entries.mapNotNull { key -> criteria.firstOrNull { it.criterion == key.key } }
    val rest = criteria.filterNot { row -> EvaluationCriterionKey.entries.any { it.key == row.criterion } }
    return known + rest
}
