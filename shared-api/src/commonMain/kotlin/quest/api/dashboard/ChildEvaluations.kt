package quest.api.dashboard

import kotlinx.serialization.Serializable

/**
 * Feature 33 `teacher evaluation of a child` (docs/features/33-teacher-evaluation-of-child.md), the **staff** half:
 * the Admin's rounds, the teacher's own page and the leaders' tracking board. The parent's half is
 * `quest.api.dto.ParentEvaluation`, and it is a different shape on purpose — it carries words, never numbers.
 *
 * Behind the `childEvaluations` flag. `evaluations.self` is TEACHER (her own children), `evaluations.board` is
 * ADMIN, MANAGERIAL, COORDINATOR, FLOOR_SUPERVISOR and TEACHER — the permission only opens the door, and which
 * teachers a caller sees is the attendance board's own scope ([BoardScope], feature 31's rule exactly) — and
 * `evaluations.rounds` is the Admin's alone.
 *
 * Dates: [EvaluationRound.opensOn] is `yyyy-MM-dd` in the school's zone, [EvaluationRound.dueAt] and every other
 * time is an ISO-8601 instant, and [EvaluationRound.timeZone] says which zone to render them in.
 */

/** The four criteria, in the order every screen reads them. The key is what [EvaluationCriterionKeys] names. */
object EvaluationCriterionKeys {
    const val PARTICIPATION = "participation"
    const val EFFORT = "effort"
    const val BEHAVIOUR = "behaviour"
    const val UNDERSTANDING = "understanding"
    val ALL = listOf(PARTICIPATION, EFFORT, BEHAVIOUR, UNDERSTANDING)
}

/**
 * The four-point scale, as the **word key** a client translates. Staff screens may also show the 1–4 value; the
 * parent is only ever given the word ([quest.api.dto.ParentEvaluation]).
 */
object EvaluationWords {
    const val EXCELLENT = "excellent"        // 4
    const val GOOD = "good"                  // 3
    const val DEVELOPING = "developing"      // 2
    const val NEEDS_SUPPORT = "needsSupport" // 1
}

/** Where one evaluation is in the workflow — the teacher's and the leader's view of it, never the parent's. */
object EvaluationState {
    const val DRAFT = "DRAFT"
    const val SUBMITTED = "SUBMITTED"
    const val RETURNED = "RETURNED"
    const val APPROVED = "APPROVED"
}

/**
 * A row's one **compliance status**, computed from the round's deadline and the review — never stored, so moving the
 * deadline re-reads every row. A draft counts as [MISSING]: nothing has been handed in.
 */
object EvaluationStatus {
    const val ON_TIME = "ON_TIME"
    const val LATE = "LATE"
    const val MISSING = "MISSING"
    const val RETURNED = "RETURNED"
    const val APPROVED = "APPROVED"
}

/**
 * One evaluation round. [released] is the one-way door to the parents: before it, no parent sees anything of the
 * round; the Admin may edit the round until then and releases it once (a second release answers `409`).
 */
@Serializable
data class EvaluationRound(
    val id: String,
    val nameEn: String,
    val nameAr: String,
    val opensOn: String,
    val dueAt: String,
    val remindHoursBefore: Int,
    val timeZone: String,
    val released: Boolean = false,
    val releasedAt: String? = null,
    val releasedBy: String? = null,
    val releasedByName: String? = null,
)

/**
 * `POST /admin/evaluation-rounds`. [dueAt] is read in the school's own zone: either a local date and time
 * (`2026-11-05T12:00`) or a full instant (`2026-11-05T09:00:00Z`). [opensOn] defaults to today.
 */
@Serializable
data class CreateEvaluationRoundRequest(
    val nameEn: String,
    val nameAr: String,
    val opensOn: String? = null,
    val dueAt: String,
    val remindHoursBefore: Int? = null,
)

/** `PUT /admin/evaluation-rounds/{id}`: an absent field keeps what the round holds. A released round answers `409`. */
@Serializable
data class UpdateEvaluationRoundRequest(
    val nameEn: String? = null,
    val nameAr: String? = null,
    val opensOn: String? = null,
    val dueAt: String? = null,
    val remindHoursBefore: Int? = null,
)

/**
 * One (class, subject, child) a teacher is expected to evaluate in a round. [evaluationId] is absent until she has
 * saved something, which is also the only time [state] is absent; [status] is [EvaluationStatus.MISSING] while
 * nothing has been submitted, a draft included. The four ratings are 1–4 and absent while unanswered.
 */
@Serializable
data class ChildEvaluationRow(
    val evaluationId: String? = null,
    val roundId: String,
    val classId: String,
    val className: String,
    val subject: String,
    val childId: String,
    val childName: String,
    val status: String,
    val state: String? = null,
    val participation: Int? = null,
    val effort: Int? = null,
    val behaviour: Int? = null,
    val understanding: Int? = null,
    val comment: String? = null,
    val goingWell: String? = null,
    val nextStep: String? = null,
    val submittedAt: String? = null,
    val reviewedBy: String? = null,
    val reviewedByName: String? = null,
    val reviewedAt: String? = null,
    val reviewComment: String? = null,
)

/** The five cards. Each is the number of rows its filter shows, so a card and the table never disagree. */
@Serializable
data class EvaluationCounts(
    val onTime: Int,
    val late: Int,
    val missing: Int,
    val returned: Int,
    val approved: Int,
)

/**
 * `GET /teacher/evaluations?round=` — her expected rows for one round. [round] is absent only while the school has
 * no round at all, and `?round=` names one by id; without it the round that is running now is answered.
 */
@Serializable
data class TeacherEvaluations(
    val round: EvaluationRound? = null,
    val rounds: List<EvaluationRound> = emptyList(),
    val counts: EvaluationCounts,
    val rows: List<ChildEvaluationRow> = emptyList(),
)

/** `PUT /teacher/evaluations/{roundId}/{classId}/{subject}/{childId}` — a draft, saved as often as she likes. */
@Serializable
data class SaveChildEvaluationRequest(
    val participation: Int? = null,
    val effort: Int? = null,
    val behaviour: Int? = null,
    val understanding: Int? = null,
    val comment: String? = null,
    val goingWell: String? = null,
    val nextStep: String? = null,
)

/**
 * One teacher on the leaders' board. [rows] is filled by `GET /evaluations/board/{teacherId}?round=` and empty on
 * the board itself, which is counts only — a board of a whole school would otherwise carry every child's comment.
 */
@Serializable
data class EvaluationBoardRow(
    val teacherId: String,
    val fullName: String,
    val counts: EvaluationCounts,
    val rows: List<ChildEvaluationRow> = emptyList(),
)

/** `GET /evaluations/board?round=` and `GET /evaluations/board/{teacherId}?round=`. [scope] is [BoardScope]'s. */
@Serializable
data class EvaluationBoard(
    val round: EvaluationRound? = null,
    val rounds: List<EvaluationRound> = emptyList(),
    val scope: String,
    val counts: EvaluationCounts,
    val rows: List<EvaluationBoardRow> = emptyList(),
)

/**
 * `POST /evaluations/{id}/review` — [decision] is `APPROVE` or `RETURN`, and a returned evaluation carries the
 * leader's [comment] back to the teacher with an `evaluation.returned` notification. A review is a decision, so a
 * second one on the same row answers `409`.
 */
@Serializable
data class ReviewEvaluationRequest(val decision: String, val comment: String? = null)

/** The two decisions a leader may take on a submitted evaluation. */
object EvaluationDecision {
    const val APPROVE = "APPROVE"
    const val RETURN = "RETURN"
}

/**
 * Feature 33: the query of an evaluation reminder's link, `/notifications?evaluation=reminder&round=…&missing=…`.
 * Both clients read it to word the row in the reader's language, as they do feature 31's [MaterialLink].
 */
object EvaluationLink {
    const val TYPE = "evaluation"
    const val ROUND = "round"
    const val MISSING = "missing"
    const val REMINDER = "reminder"
    const val RETURNED = "returned"
}
