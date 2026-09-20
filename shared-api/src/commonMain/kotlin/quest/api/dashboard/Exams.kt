package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Subject

/**
 * N4.3 `backend/exams` — `docs/teacher-flow.md` step 10 and the teacher prompt §8: an exam is a lesson with
 * `type = "exam"` and its own rules, so everything here sits beside [Grading] rather than repeating it.
 *
 * **What an exam adds to a lesson**: a window it is visible and playable in, one level (or a *mixed* paper assembled
 * from the generated ones), exactly one resumable attempt per child, no hints and no numbers while the child sits
 * it, and a release that is either automatic when the window closes or the teacher's own. The *score* is §7's, to
 * the digit — [ExamChildResult.percent] is the same 0-100 [quest.api.dashboard.ChildResult.score] a homework gets,
 * computed by the same scorer from the same attempts and the same marks. What differs is the weight it carries in
 * the child's level (`Bands.EXAM_WEIGHT`, ×2) and who may see it when.
 *
 * The whole area is behind the `exams` flag and answers 404 while a school has it off.
 */

/** `1`, `2`, `3`, or a `mixed` paper assembled from the three generated levels. */
object ExamLevels {
    const val ONE = "1"
    const val TWO = "2"
    const val THREE = "3"
    const val MIXED = "mixed"
    val ALL = listOf(ONE, TWO, THREE, MIXED)
}

/** When the parents get to see it: on its own the moment the window closes, or when the teacher says so. */
object ExamRelease {
    const val AUTO_ON_CLOSE = "auto_on_close"
    const val MANUAL = "manual"
    val ALL = listOf(AUTO_ON_CLOSE, MANUAL)
}

/**
 * The one word the class page's State column says about a whole exam — not [ExamState], which answers a different
 * question about one child.
 *
 * The server computes it ([ExamRow.state]) so that the dashboard and the server can never disagree about what
 * "open" means. [RELEASED] beats the window — the parents have the scores, whatever the clock says — and [DRAFT]
 * beats everything else, including a published exam with no usable window: nobody can have sat a paper that never
 * opened, so calling that `closed` would hide it behind the one word a teacher never looks at twice.
 */
object ExamRowState {
    const val DRAFT = "draft"
    const val SCHEDULED = "scheduled"
    const val OPEN = "open"
    const val CLOSED = "closed"
    const val RELEASED = "released"
    val ALL = listOf(DRAFT, SCHEDULED, OPEN, CLOSED, RELEASED)
}

/**
 * The settings sheet of one exam — §8's `ExamSettings(lessonId, opensAt, closesAt, level, hintsOff, releaseMode)`
 * with the lesson's own identity beside it so one read fills the whole screen.
 *
 * [singleAttempt], [hintsOff] and [numbersOff] are §8's rules rather than choices and are always true; they are on
 * the wire because the player has to be told, and because a later phase may loosen one of them for a school.
 */
@Serializable
data class ExamSettings(
    val examId: String,
    val title: String? = null,
    val classId: String? = null,
    val className: String? = null,
    val subject: Subject? = null,
    val date: LocalDate? = null,
    val opensAt: Long,
    val closesAt: Long,
    /** One of [ExamLevels]. */
    val level: String = ExamLevels.MIXED,
    /** How long a child has once she starts, when the school sets one. Advisory: the window is what is enforced. */
    val durationMinutes: Int? = null,
    val singleAttempt: Boolean = true,
    val hintsOff: Boolean = true,
    val numbersOff: Boolean = true,
    /** One of [ExamRelease]. */
    val releaseMode: String = ExamRelease.AUTO_ON_CLOSE,
    /** The lesson's own status: `draft`, `review`, `published`, `error`. */
    val status: String = "draft",
    val released: Boolean = false,
    val releasedAt: Long? = null,
    /** Whether the window is open *now*, as the server reads the clock. */
    val open: Boolean = false,
)

/**
 * One row of the class page's Exams tab (`GET /teacher/classes/{id}/exams`), and the whole of
 * `GET /teacher/exams/{id}`: [ExamSettings] with the four things the tab draws beside it.
 *
 * The numbers are here because the alternative was the dashboard reading `/teacher/exams/{id}/results` once per row
 * — a full scoring pass, its plays, its attempts and its marks, six times over, for three integers. The server
 * computes all of them for the whole tab in a fixed number of statements instead.
 *
 * [roster] is the class register, [sat] the children with a sitting, and [needsMarking] the open stops still waiting
 * for the teacher — §7's count, over §8's paper. [state] is one of [ExamRowState].
 */
@Serializable
data class ExamRow(
    val examId: String,
    val title: String? = null,
    val classId: String? = null,
    val className: String? = null,
    val subject: Subject? = null,
    val date: LocalDate? = null,
    val opensAt: Long,
    val closesAt: Long,
    /** One of [ExamLevels]. */
    val level: String = ExamLevels.MIXED,
    val durationMinutes: Int? = null,
    val singleAttempt: Boolean = true,
    val hintsOff: Boolean = true,
    val numbersOff: Boolean = true,
    /** One of [ExamRelease]. */
    val releaseMode: String = ExamRelease.AUTO_ON_CLOSE,
    /** The lesson's own status: `draft`, `review`, `published`, `error`. */
    val status: String = "draft",
    val released: Boolean = false,
    val releasedAt: Long? = null,
    /** Whether the window is open *now*, as the server reads the clock. */
    val open: Boolean = false,
    /** One of [ExamRowState], decided by the server's clock at request time. */
    val state: String = ExamRowState.DRAFT,
    val roster: Int = 0,
    val sat: Int = 0,
    val needsMarking: Int = 0,
)

/**
 * `POST /teacher/classes/{id}/exams`: the same editor as a lesson, plus the window and the level.
 *
 * [source] is a lesson source (`pdf`, `slides`, `images`, `manual`) and runs the same pipeline — the exam is a
 * lesson, so there is one pipeline and one cache, not two.
 */
@Serializable
data class CreateExamRequest(
    val title: String,
    val opensAt: Long,
    val closesAt: Long,
    val level: String = ExamLevels.MIXED,
    val source: String = "manual",
    val durationMinutes: Int? = null,
    val releaseMode: String = ExamRelease.AUTO_ON_CLOSE,
    val notes: String? = null,
    val practiceLength: Int? = null,
)

/** `PATCH /teacher/exams/{id}`: only the fields that are present are written, and only while the window is shut. */
@Serializable
data class UpdateExamRequest(
    val title: String? = null,
    val opensAt: Long? = null,
    val closesAt: Long? = null,
    val level: String? = null,
    val durationMinutes: Int? = null,
    val releaseMode: String? = null,
)

/** Where one child stands on one exam. `reopened` is the teacher's second chance for an absent or cut-off child. */
@Serializable
enum class ExamState {
    @SerialName("absent") ABSENT,
    @SerialName("started") STARTED,
    @SerialName("submitted") SUBMITTED,
    @SerialName("reopened") REOPENED,
}

/**
 * One row of the exam results table (§8's `ExamResult` per child).
 *
 * [score] out of [maxScore] is the stars she earned; [percent] is §7's 0-100 and is the number the band, the
 * gradebook and the child's level are all taken from. [needsMarking] counts her open stops the teacher has not
 * looked at yet — until it is zero, [percent] describes only what could be scored automatically.
 */
@Serializable
data class ExamChildResult(
    val childId: String,
    val name: String,
    val state: ExamState = ExamState.ABSENT,
    val score: Int = 0,
    val maxScore: Int = 0,
    val percent: Int? = null,
    val band: Level? = null,
    /** Start to submit, in seconds; null while she is still in it or was never in it. */
    val secondsTaken: Int? = null,
    val startedAt: Long? = null,
    /**
     * The last answer the sitting took. For a child still inside the paper it is the only thing that separates
     * "working on it" from "walked away from it ten minutes ago".
     */
    val lastSeenAt: Long? = null,
    val submittedAt: Long? = null,
    val needsMarking: Int = 0,
    val reopened: Boolean = false,
    val comment: String? = null,
)

/** One column of the distribution chart: how many children landed in each of §7's four bands. */
@Serializable
data class ExamBand(val band: Level, val children: Int = 0)

/**
 * Per-question difficulty — §8's "which questions most children missed".
 *
 * [answered] is how many children reached the question at all, so [missedPercent] is out of those rather than out
 * of the roster: a question nobody got to is not a question everybody failed. An open stop has no right answer and
 * reports [averageStars] from the teacher's marks instead.
 */
@Serializable
data class ExamQuestion(
    val stopId: String,
    val title: String,
    val type: String,
    val open: Boolean = false,
    val answered: Int = 0,
    val correct: Int = 0,
    val averageStars: Double? = null,
    val missedPercent: Int? = null,
)

/** `GET /teacher/exams/{id}/results`; the same shape backs `results.csv` and `results.xlsx`. */
@Serializable
data class ExamResults(
    val examId: String,
    val title: String? = null,
    val classId: String? = null,
    val className: String? = null,
    val subject: Subject? = null,
    val date: LocalDate? = null,
    val settings: ExamSettings,
    val released: Boolean = false,
    val releasedAt: Long? = null,
    val roster: Int = 0,
    val sat: Int = 0,
    val submitted: Int = 0,
    val absent: Int = 0,
    /** Over the children who have a percent, not over the roster. */
    val classAverage: Int? = null,
    val needsMarking: Int = 0,
    val distribution: List<ExamBand> = emptyList(),
    val questions: List<ExamQuestion> = emptyList(),
    val children: List<ExamChildResult> = emptyList(),
    /** The subset of [children] who never sat it — §8's "Re-open for this child" list. */
    val absentees: List<ExamChildResult> = emptyList(),
)

/** `POST /teacher/exams/{id}/reopen/{childId}`: one more sitting for one child, until [closesAt]. */
@Serializable
data class ExamReopen(
    val examId: String,
    val childId: String,
    val closesAt: Long,
    val reopenedAt: Long,
)
