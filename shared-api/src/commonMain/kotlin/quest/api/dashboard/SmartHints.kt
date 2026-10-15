package quest.api.dashboard

import kotlinx.serialization.Serializable
import quest.api.dto.Play

/**
 * Feature 30 — what the teacher reads, edits and approves before any of it reaches a child
 * (`docs/features/30-smart-hints-targeted-practice.md`).
 *
 * Everything here is written by the lesson's `HINTS` pipeline step, at build time and nowhere else. The dashboard
 * screen is the stop editor's "Hints" panel plus a list of the practice sets; the app never calls these routes.
 *
 * All of it is behind the `smartHints` flag and scoped by `TeacherScope`: 404 while the flag is off for the school,
 * and 404 for a lesson that is not this teacher's.
 */
@Serializable
data class SmartHintsView(
    val lessonId: String,
    /** Every stop the step wrote hints for, in play then stop order. Empty until the step has run. */
    val hints: List<StopHintsView> = emptyList(),
    /** One per confirmed skill the step could write a practice set for. */
    val practice: List<PracticePlayView> = emptyList(),
)

/**
 * The two hints of one stop. [question] is what the child is asked, so the teacher can judge the hints without
 * opening the stop, and [answer] is what they may never say — the words the validator rejected a hint for
 * containing.
 */
@Serializable
data class StopHintsView(
    val stopId: String,
    val level: Int,
    val variant: Int,
    val stopType: String,
    val question: String,
    val answer: String,
    val hints: List<String>,
    val approved: Boolean = false,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
)

/** One targeted practice set: level 1, [Play.PRACTICE_VARIANT] or more, 4–5 answerable stops on one skill. */
@Serializable
data class PracticePlayView(
    val playId: String,
    val skillId: String,
    val skillName: String,
    val variant: Int,
    val play: Play,
    val approved: Boolean = false,
    val approvedBy: String? = null,
    val approvedAt: Long? = null,
)

/** `PUT /teacher/lessons/{id}/smart-hints/{stopId}` — exactly two hints, each at most 90 characters. */
@Serializable data class SaveStopHintsRequest(val hints: List<String>)

/** `POST /teacher/lessons/{id}/smart-hints/approve` — the named stops, or every stop when [stopIds] is null. */
@Serializable data class ApproveHintsRequest(val stopIds: List<String>? = null)
