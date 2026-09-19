package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.Subject

/**
 * N4.1 `backend/scoring-marks-levels` — `docs/teacher-flow.md` step 9 and the teacher prompt §7: the Results page,
 * the class gradebook, the child page, the marks a teacher saves and the release that lets a parent see any of it.
 *
 * Kept in its own file beside [Teacher], for the same reason that one is separate: the whole area sits behind the
 * `gradebook` and `openStopMarking` flags and answers 404 while a school has them off, so a reader of the contract
 * can see at a glance which part of the dashboard a school may not have.
 *
 * Scores are 0-100 and bands are [Level]; both are computed on the server from the child's attempts and the
 * teacher's marks, and the thresholds are constants in `server/.../grading/Bands.java`.
 */

/** §7's four words for where a child is, over a 0-100 score. */
@Serializable
enum class Level {
    @SerialName("emerging") EMERGING,
    @SerialName("developing") DEVELOPING,
    @SerialName("secure") SECURE,
    @SerialName("exceeding") EXCEEDING,
}

/** Which way the last lessons point. Null below four scored lessons — two lessons are not a direction. */
@Serializable
enum class Trend { @SerialName("up") UP, @SerialName("flat") FLAT, @SerialName("down") DOWN }

// -------------------------------------------------------------------------------------------------------------
// Marking (§7)
// -------------------------------------------------------------------------------------------------------------

/**
 * One mark. [stopId] null means the mark is about the whole lesson — that is where [score] (the override that keeps
 * the automatic score visible beside it) and the comment to the parent live. On a stop, [stars] is 1-3 and [score]
 * is ignored: §7 marks open stops in stars and the stop's score follows from them.
 *
 * [stars], [score] and [comment] all null **deletes** the mark, which is how a star given by accident is taken back.
 */
@Serializable
data class MarkInput(
    val childId: String,
    val lessonId: String,
    val stopId: String? = null,
    val stars: Int? = null,
    val score: Int? = null,
    val comment: String? = null,
)

/** `PUT /teacher/marks`: a page of marking saved in one request, so one screen is one write. */
@Serializable
data class SaveMarksRequest(val marks: List<MarkInput> = emptyList())

@Serializable
data class TeacherMark(
    val childId: String,
    val lessonId: String,
    val stopId: String? = null,
    val stars: Int? = null,
    val score: Int? = null,
    val comment: String? = null,
    val markedBy: String? = null,
    val markedAt: Long = 0,
)

// -------------------------------------------------------------------------------------------------------------
// Release (§7)
// -------------------------------------------------------------------------------------------------------------

/**
 * `POST /teacher/lessons/{id}/release`: releases the lesson's results to the parents of the whole section at once.
 *
 * `released = false` withdraws it. That is the only way to re-mark a released lesson — while it is released, `PUT
 * /teacher/marks` answers 409, because the score and the comment a parent has already been shown must not change
 * under her.
 */
@Serializable
data class ReleaseRequest(val released: Boolean = true)

@Serializable
data class LessonRelease(
    val lessonId: String,
    val released: Boolean,
    val releasedAt: Long? = null,
    /** How many children of the section it was released for. */
    val children: Int = 0,
)

// -------------------------------------------------------------------------------------------------------------
// Results (§7, step 9)
// -------------------------------------------------------------------------------------------------------------

/** One stop of the lesson, in play order, so every child's row has the same columns. */
@Serializable
data class ResultStop(
    val stopId: String,
    val title: String,
    val type: String,
    val level: Int,
    /** A retell, open answer, drawing or free writing: complete but unscored until the teacher marks it. */
    val open: Boolean = false,
)

/** One child on one stop. [accuracy] is her stars as a percentage; [score] is what §7's rules made of them. */
@Serializable
data class ChildStopResult(
    val stopId: String,
    val attempted: Boolean = false,
    val firstTryCorrect: Boolean? = null,
    val stars: Int = 0,
    val attempts: Int = 0,
    val accuracy: Int? = null,
    val score: Int? = null,
    val markStars: Int? = null,
    val markComment: String? = null,
    val needsMarking: Boolean = false,
    /** `/media/child/{id}` for the retell or drawing she saved on this stop; fetched with the caller's own token. */
    val workUrl: String? = null,
)

/** One row of the Results page: §7's `HomeworkScore` with the marks and the saved work beside it. */
@Serializable
data class ChildResult(
    val childId: String,
    val name: String,
    val classId: String? = null,
    val attempted: Boolean = false,
    val levelReached: Int = 0,
    val autoScore: Int? = null,
    val teacherScore: Int? = null,
    /** The override when she set one, the automatic score otherwise — what the gradebook and the parent see. */
    val score: Int? = null,
    val band: Level? = null,
    val starsEarned: Int = 0,
    val starsTotal: Int = 0,
    val completion: Int = 0,
    val needsMarking: Int = 0,
    /** The teacher's line to the parent about this lesson. */
    val comment: String? = null,
    val stops: List<ChildStopResult> = emptyList(),
)

/** `GET /teacher/lessons/{id}/results`; the same shape backs `results.csv` and `results.xlsx`. */
@Serializable
data class LessonResults(
    val lessonId: String,
    val title: String? = null,
    val classId: String? = null,
    val className: String? = null,
    val subject: Subject? = null,
    val date: LocalDate? = null,
    val type: String = "homework",
    val released: Boolean = false,
    val releasedAt: Long? = null,
    /** Over the children who have a score, not over the roster. */
    val classAverage: Int? = null,
    val played: Int = 0,
    val needsMarking: Int = 0,
    val stops: List<ResultStop> = emptyList(),
    val children: List<ChildResult> = emptyList(),
)

// -------------------------------------------------------------------------------------------------------------
// Gradebook (§7)
// -------------------------------------------------------------------------------------------------------------

/** One column of the grid. [needsMarking] is how many of its children are still waiting for the teacher. */
@Serializable
data class GradebookLesson(
    val lessonId: String,
    val title: String? = null,
    val date: LocalDate,
    val subject: Subject? = null,
    val type: String = "homework",
    val released: Boolean = false,
    val classAverage: Int? = null,
    val needsMarking: Int = 0,
)

/** One cell. [teacherScore] sits beside [autoScore] rather than replacing it — §7's "keeps auto visible". */
@Serializable
data class GradebookCell(
    val lessonId: String,
    val attempted: Boolean = false,
    val autoScore: Int? = null,
    val teacherScore: Int? = null,
    val score: Int? = null,
    val band: Level? = null,
    val needsMarking: Boolean = false,
)

/** One row: a child, her cells in the same order as [Gradebook.lessons], and the level §7 rolls up from them. */
@Serializable
data class GradebookChild(
    val childId: String,
    val name: String,
    val average: Int? = null,
    val band: Level? = null,
    val trend: Trend? = null,
    val cells: List<GradebookCell> = emptyList(),
)

/** `GET /teacher/classes/{id}/gradebook?from&to`; the same shape backs `gradebook.csv` and `gradebook.xlsx`. */
@Serializable
data class Gradebook(
    val classId: String,
    val className: String? = null,
    val subject: Subject? = null,
    val from: LocalDate,
    val to: LocalDate,
    val lessons: List<GradebookLesson> = emptyList(),
    val children: List<GradebookChild> = emptyList(),
    val needsMarking: Int = 0,
)

// -------------------------------------------------------------------------------------------------------------
// The child page (§7)
// -------------------------------------------------------------------------------------------------------------

/** §7's `ChildLevel(childId, subject, band, trend, computedAt)`, one per subject she has been scored in. */
@Serializable
data class ChildLevel(
    val subject: Subject,
    val band: Level? = null,
    val trend: Trend? = null,
    val average: Int? = null,
    val lessons: Int = 0,
)

/** One point of the score chart, oldest first, so the dashboard plots it without sorting. */
@Serializable
data class ChildTrendPoint(
    val lessonId: String,
    val title: String? = null,
    val date: LocalDate,
    val subject: Subject? = null,
    val score: Int? = null,
    val band: Level? = null,
    val released: Boolean = false,
)

/** A comment the teacher left, on a stop or on the lesson, newest first. */
@Serializable
data class ChildComment(
    val lessonId: String,
    val lessonTitle: String? = null,
    val stopId: String? = null,
    val stars: Int? = null,
    val comment: String,
    val markedAt: Long,
)

/** Saved open-stop work: the same `/media/child/{id}` link the student timeline hands out. */
@Serializable
data class ChildWork(
    val id: String,
    val url: String,
    val kind: String,
    val stopId: String,
    val createdAt: Long,
)

/** `GET /teacher/children/{id}`: band and trend per subject, the chart, the comments and the saved work. */
@Serializable
data class ChildReport(
    val childId: String,
    val name: String,
    val classId: String? = null,
    val className: String? = null,
    val avatarColor: String? = null,
    val levels: List<ChildLevel> = emptyList(),
    val trend: List<ChildTrendPoint> = emptyList(),
    val comments: List<ChildComment> = emptyList(),
    val work: List<ChildWork> = emptyList(),
)
