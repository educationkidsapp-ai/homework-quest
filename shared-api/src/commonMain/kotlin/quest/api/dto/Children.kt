package quest.api.dto

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class Child(
    val id: String,
    val name: String,
    val avatarColor: String,        // sky | sun | mint | lavender (Pip in four colours)
    val curriculum: Curriculum,
    val grade: Int,
    val languages: List<String> = listOf("en"),
    /** The tenant the child belongs to; pre-tenancy children are in the default school. */
    val schoolId: String = "default",
) { val course: Course get() = Course(curriculum, grade) }

/**
 * `schoolCode` is the 6-character code a parent types to join a school; without it the child lands in the default
 * school.
 *
 * `joinCode` is the newer, narrower one (V7, `docs/teacher-flow.md` §2): the code printed on a **class**'s card. It
 * puts the child straight into that section, and the school, curriculum and grade all come from the class — whatever
 * `curriculum`, `grade` and `schoolCode` say is ignored, because the card is the more specific answer. Both fields
 * stay so the app keeps working unchanged until it adopts the class code.
 */
@Serializable
data class CreateChildRequest(
    val name: String,
    val avatarColor: String,
    val curriculum: Curriculum,
    val grade: Int,
    val languages: List<String> = listOf("en"),
    val schoolCode: String? = null,
    val joinCode: String? = null,
)

@Serializable
data class UpdateChildRequest(val name: String? = null, val avatarColor: String? = null, val curriculum: Curriculum? = null, val grade: Int? = null, val languages: List<String>? = null)

/** One answered stop, uploaded in batches. `answerJson` is the raw answer payload (option ids, order, hotspot ids…). */
@Serializable
data class AttemptUpload(
    val id: String,
    val stopId: String,
    val lessonId: String,
    val level: Int,
    val answerJson: String,
    val correct: Boolean,
    val attemptNumber: Int,
    val mistakes: Int,
    val stars: Int,
    val answeredAt: Long,
)

@Serializable data class AttemptAck(val accepted: Int)
@Serializable data class MediaRef(val id: String, val url: String, val kind: MediaKind)

@Serializable
data class ProgressResponse(
    val childId: String,
    val skills: List<SkillProgress>,
    val weakSkillIds: List<String>,
    val streakDays: Int,
    val lastPlayedDate: LocalDate?,
    val stickers: List<String>,
    /**
     * N4.1 (teacher prompt §7): the lessons whose results the teacher has **released**, with the score, the band and
     * her comment. Empty until she releases one, and it carries nothing about a lesson she has not.
     *
     * Parent mode only. §6's rule is unchanged: the child sees stars, a sticker and a certificate, never a number,
     * so this belongs behind the parent gate in the app and must not reach a child-mode screen.
     */
    val results: List<ReleasedResult> = emptyList(),
)

/** One question/stop outcome and teacher note as a parent sees it. */
@Serializable
data class ReleasedStopResult(
    val stopId: String,
    val title: String,
    val correct: Boolean,
    val comment: String? = null,
)

/**
 * One released homework or exam result as a parent sees it (teacher prompt §7, `docs/teacher-flow.md` step 9).
 *
 * [score] is 0-100 — the teacher's override when she set one, the automatic score otherwise — and [band] is one of
 * `emerging`, `developing`, `secure`, `exceeding` (`server/.../grading/Bands.java`). [comment] is the one line she
 * wrote to the parent, or null.
 */
@Serializable
data class ReleasedResult(
    val lessonId: String,
    val title: String?,
    val date: LocalDate,
    val subject: Subject,
    val score: Int?,
    val band: String?,
    val comment: String? = null,
    val releasedAt: Long,
    val stops: List<ReleasedStopResult> = emptyList(),
)

@Serializable
data class SkillProgress(val skillId: String, val name: String, val subject: Subject, val band: String?, val firstTryAccuracyWords: String?, val attempts: Int, val lastPractised: Long?)
