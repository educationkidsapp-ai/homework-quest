package quest.api.dto

import kotlinx.serialization.Serializable

/**
 * Feature 33 `teacher evaluation of a child` (docs/features/33-teacher-evaluation-of-child.md), the **parent's**
 * half: `GET /children/{id}/evaluations`, read on the app's "Teacher evaluations" screen behind the parent gate.
 *
 * **There is no number here, and there is no draft.** The server answers only the evaluations a leader has
 * *approved*, of rounds the Admin has *released*, and it maps each criterion's 1–4 rating to a word key on the
 * server side ([EvaluationWord]). A rating, a state, a reviewer and a review comment are staff business
 * (`quest.api.dashboard.ChildEvaluations`) and none of them has a field in these two classes at all — which is what
 * `ChildEvaluationsApiTest` sweeps the answer for, field by field and value by value.
 *
 * **Reply privately** on the screen is the ordinary one-to-one chat with [ParentEvaluation.teacherId]
 * (`ContentApi.sendChatMessage`), not a new kind of message.
 *
 * The **round list** is implied by these rows rather than fetched: every row names its round
 * ([ParentEvaluation.roundId] and the round's name in both languages), so the screen groups by round and needs no
 * second call. Rows arrive newest round first, then by subject.
 */
@Serializable
data class ParentEvaluation(
    val roundId: String,
    val roundName: String,
    val roundNameAr: String,
    val subject: String,
    val teacherId: String,
    val teacherName: String,
    val criteria: List<EvaluationCriterion> = emptyList(),
    val comment: String? = null,
    val goingWell: String? = null,
    val nextStep: String? = null,
    val releasedAt: String,
)

/**
 * One criterion as a parent reads it: which criterion, and the word for it. [criterion] is one of `participation`,
 * `effort`, `behaviour`, `understanding` and [word] one of [EvaluationWord]'s four keys — both are keys the client
 * translates, so the sentence is in her language and the server never ships a number.
 */
@Serializable
data class EvaluationCriterion(val criterion: String, val word: String)

/** The four points of the scale, as the keys a client translates. The parent never sees the value behind them. */
object EvaluationWord {
    const val EXCELLENT = "excellent"
    const val GOOD = "good"
    const val DEVELOPING = "developing"
    const val NEEDS_SUPPORT = "needsSupport"
}
