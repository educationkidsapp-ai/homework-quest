package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import quest.api.dto.Curriculum
import quest.api.dto.MediaKind
import quest.api.dto.Play
import quest.api.dto.SourceKind
import quest.api.dto.Stop
import quest.api.dto.Subject
import quest.api.dto.Theme

/**
 * P4.0 `backend/teacher-contract` — §5 Teacher and §6 screens 11–16.
 *
 * Kept in its own file rather than appended to [DashboardApi]'s: the teacher screens are one feature area, two of
 * them sit behind feature flags (`teacherQuestions`, `announcements`) and the routes answer 404 while a flag is off,
 * so a reader of the contract can see at a glance which half of the dashboard a school may not have.
 */

// ---------------------------------------------------------------------------------------------------------------
// Profile and the restricted chooser (§5, §6 screen 13)
// ---------------------------------------------------------------------------------------------------------------

/**
 * `PUT /teacher/profile` and `PUT /admin/users/{id}/teacher-profile`: only the fields that are present are written,
 * so the dashboard can save one section of the form without sending the rest.
 *
 * [photoUrl] must be an `https://` URL — it is rendered into an `img src` by the dashboard and by the app's teacher
 * island, and the server refuses anything else (the same `SafeText` rule as a school's logo).
 */
@Serializable
data class UpdateTeacherProfileRequest(
    val displayName: String? = null,
    val photoUrl: String? = null,
    val subjects: List<Subject>? = null,
    val curriculum: Curriculum? = null,
    val grades: List<Int>? = null,
    val bioEn: String? = null,
    val bioAr: String? = null,
)

/**
 * `GET /teacher/options` (§6 screen 13): what the New lesson chooser is allowed to offer this teacher — her
 * curriculum, her grades, her subjects, and the classes she already owns. The server refuses a lesson outside them
 * (`TenantGuard.lessonCreator`), so the chooser showing anything else would be an action the server would reject.
 *
 * A teacher whose profile has not been filled in yet gets empty lists and [complete] false; the dashboard shows the
 * "ask your school to finish your profile" empty state rather than an unusable chooser.
 */
@Serializable
data class TeacherOptions(
    val curriculum: Curriculum? = null,
    val grades: List<Int> = emptyList(),
    val subjects: List<Subject> = emptyList(),
    val classes: List<SchoolClass> = emptyList(),
    val complete: Boolean = false,
)

// ---------------------------------------------------------------------------------------------------------------
// My lessons — the per-class calendar (§6 screen 12)
// ---------------------------------------------------------------------------------------------------------------

/**
 * One day of a class's month. [lessonId] and [status] are null when nothing is dated that day.
 *
 * [schoolDay] is what makes [gap] meaningful: a school day with no lesson is a gap the calendar marks, a weekend is
 * not. **Sunday–Thursday is assumed** — the Gulf school week, which is what the QA schools run. It is not yet
 * configurable per school; when a school on a Monday–Friday week is onboarded, `schools` gains a `school_week`
 * column and this field is computed from it instead. Nothing on the client should hard-code the same assumption.
 */
@Serializable
data class ClassCalendarDay(
    val date: LocalDate,
    val lessonId: String? = null,
    val status: String? = null,
    val schoolDay: Boolean = true,
    val gap: Boolean = false,
)

/** `GET /teacher/classes/{classId}/calendar?year=&month=` (§6 screen 12): the month, with the gaps flagged. */
@Serializable
data class ClassCalendar(
    val classId: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val year: Int,
    val month: Int,
    val days: List<ClassCalendarDay> = emptyList(),
    val gaps: Int = 0,
)

// ---------------------------------------------------------------------------------------------------------------
// Questions to students (§6 screen 14, flag `teacherQuestions`)
// ---------------------------------------------------------------------------------------------------------------

/** `POST /teacher/questions`: a draft. [classIds] must be classes the caller owns; the window is inclusive at both ends. */
@Serializable
data class CreateTeacherQuestionRequest(
    val title: String,
    val stops: List<Stop> = emptyList(),
    val classIds: List<String> = emptyList(),
    val from: LocalDate,
    val to: LocalDate,
)

/** `PUT /teacher/questions/{id}`: only the fields that are present are written. 409 once the question has been sent. */
@Serializable
data class UpdateTeacherQuestionRequest(
    val title: String? = null,
    val stops: List<Stop>? = null,
    val classIds: List<String>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * A question in the teacher's list, with the summary §6 screen 14 shows next to it.
 *
 * [answeredChildren] out of [totalChildren] is "how many of the children in the chosen classes have answered at
 * least one stop"; [firstTryAccuracy] is the share of first answers that were right across every child and stop, or
 * null when nobody has answered yet — the dashboard renders it with `ProgressBands.accuracyWords`, never as a bare
 * percentage, for the same reason the parent panel does.
 */
@Serializable
data class TeacherQuestion(
    val id: String,
    val schoolId: String,
    val teacherId: String,
    val teacherName: String? = null,
    val title: String,
    val stops: List<Stop> = emptyList(),
    val classIds: List<String> = emptyList(),
    val from: LocalDate,
    val to: LocalDate,
    val createdAt: Long = 0,
    /** Null while it is a draft; set once, by `POST /teacher/questions/{id}/send`. */
    val sentAt: Long? = null,
    val totalChildren: Int = 0,
    val answeredChildren: Int = 0,
    val firstTryAccuracy: Double? = null,
)

/** One stop of one child's answers in the results table. */
@Serializable
data class TeacherQuestionStopResult(
    val stopId: String,
    val answered: Boolean = false,
    val correct: Boolean = false,
    val stars: Int = 0,
    val answeredAt: Long? = null,
)

/** One child's row of the results table (§6 screen 14). */
@Serializable
data class TeacherQuestionChildResult(
    val childId: String,
    val name: String,
    val classId: String? = null,
    val answered: Int = 0,
    val stars: Int = 0,
    val stops: List<TeacherQuestionStopResult> = emptyList(),
)

/** `GET /teacher/questions/{id}/results`: every child of the chosen classes, answered or not. */
@Serializable
data class TeacherQuestionResults(
    val questionId: String,
    val title: String,
    val stopIds: List<String> = emptyList(),
    val children: List<TeacherQuestionChildResult> = emptyList(),
    val firstTryAccuracy: Double? = null,
)

// ---------------------------------------------------------------------------------------------------------------
// Announcements (§6 screen 16, flag `announcements`)
// ---------------------------------------------------------------------------------------------------------------

/** `POST /teacher/announcements`. [classId] must be a class the caller owns; [expiresAt] null means "no end date". */
@Serializable
data class CreateAnnouncementRequest(
    val classId: String,
    val bodyEn: String,
    val bodyAr: String? = null,
    val expiresAt: Long? = null,
)

/** A note to the parents of one class, as the dashboard lists it. */
@Serializable
data class Announcement(
    val id: String,
    val schoolId: String,
    val teacherId: String,
    val teacherName: String? = null,
    val classId: String,
    val curriculum: Curriculum? = null,
    val grade: Int? = null,
    val subject: Subject? = null,
    val bodyEn: String,
    val bodyAr: String? = null,
    val publishedAt: Long = 0,
    val expiresAt: Long? = null,
    val createdAt: Long = 0,
)

// ---------------------------------------------------------------------------------------------------------------
// My students (§6 screen 15)
// ---------------------------------------------------------------------------------------------------------------

/**
 * One child in `GET /teacher/classes/{classId}/students`.
 *
 * [levelReached] is the highest level she has *completed* of any lesson of the class's school, 0 when none;
 * [weakSkills] are the `ProgressBands.NEEDS_ANOTHER_LOOK` bands, words rather than percentages, as everywhere else.
 */
@Serializable
data class ClassStudent(
    val childId: String,
    val name: String,
    val avatarColor: String = "sky",
    val starsThisWeek: Int = 0,
    val levelReached: Int = 0,
    val weakSkills: List<WeakSkill> = emptyList(),
    val lastPlayed: Long? = null,
)

/**
 * One thing a child did, in `GET /teacher/students/{childId}/timeline`.
 *
 * [kind] is `lesson.completed` (a level of a lesson finished — [lessonId], [title], [level], [starsEarned],
 * [starsTotal]) or `question.answered` (a stop of a teacher's question — [questionId], [title], [correct],
 * [stars]). An unknown kind should render as nothing rather than as the raw id; later phases add rows an older
 * dashboard build has no string for, exactly as `NeedsYouItem` does.
 */
@Serializable
data class StudentTimelineEntry(
    val kind: String,
    val at: Long,
    val title: String? = null,
    val lessonId: String? = null,
    val level: Int? = null,
    val starsEarned: Int? = null,
    val starsTotal: Int? = null,
    val questionId: String? = null,
    val stopId: String? = null,
    val correct: Boolean? = null,
    val stars: Int? = null,
)

/**
 * A retell recording or a drawing the child saved. [url] is the authenticated `/media/child/{id}` route — a TEACHER
 * or MANAGERIAL user of the child's school may read it, so the dashboard fetches it with its own bearer token.
 */
@Serializable
data class StudentMedia(
    val id: String,
    val url: String,
    val kind: MediaKind,
    val stopId: String,
    val createdAt: Long = 0,
)

/** `GET /teacher/students/{childId}/timeline?from=&to=` (§6 screen 15). */
@Serializable
data class StudentTimeline(
    val childId: String,
    val name: String,
    val from: LocalDate,
    val to: LocalDate,
    val entries: List<StudentTimelineEntry> = emptyList(),
    val media: List<StudentMedia> = emptyList(),
)

// ---------------------------------------------------------------------------------------------------------------
// The app side of a teacher's question (§6 "Mobile app additions")
// ---------------------------------------------------------------------------------------------------------------

/**
 * `GET /children/{id}/teacher-questions/{questionId}` — the stops behind a "From your teacher" island, shaped like a
 * [Play] so the app hands it straight to the existing stop player: [level] 1, [variant] 0, [kind] `teacher`.
 *
 * It is a type of its own rather than a bare [Play] because a play's `kind` is the closed `SourceKind` enum of
 * `Play.schema.json` and a teacher's question is not one of those six; [asPlay] does the one-line conversion the
 * player needs. [answeredStopIds] lets the app resume a half-finished question instead of starting it again.
 */
@Serializable
data class TeacherQuestionPlay(
    val questionId: String,
    val title: String,
    val teacherName: String,
    val teacherPhotoUrl: String? = null,
    val from: LocalDate,
    val to: LocalDate,
    val level: Int = 1,
    val variant: Int = 0,
    val kind: String = "teacher",
    val theme: Theme,
    val stops: List<Stop> = emptyList(),
    val answeredStopIds: List<String> = emptyList(),
) {
    /** The same stops as a [Play] the existing player accepts; `SourceKind.MIXED` stands in for "teacher". */
    fun asPlay(): Play = Play(level, variant, SourceKind.MIXED, theme, stops)
}

/**
 * `POST /children/{id}/teacher-questions/{questionId}/answers` — a batch, like `POST /children/{id}/attempts`:
 * idempotent on (question, child, stop), so a retry after a dropped connection changes nothing.
 */
@Serializable
data class TeacherAnswerUpload(
    val stopId: String,
    val answerJson: String = "{}",
    val correct: Boolean = false,
    val stars: Int = 0,
    val answeredAt: Long,
)

/** What the parent app shows in the Announcements card; no teacher id, no school internals. */
@Serializable
data class ParentAnnouncement(
    val id: String,
    val teacherName: String? = null,
    val teacherPhotoUrl: String? = null,
    val bodyEn: String,
    val bodyAr: String? = null,
    val publishedAt: Long = 0,
    val expiresAt: Long? = null,
)
