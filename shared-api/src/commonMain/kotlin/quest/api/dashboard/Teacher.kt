package quest.api.dashboard

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.LessonSource
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
 * not. Since N2.1 it is computed from the school's own week — Platform settings' `schoolWeek`, or the school's
 * override — so nothing on the client may assume Sunday–Thursday either.
 *
 * [status] is the same coarse vocabulary the week grid uses (see [WeekLessonStatus]).
 */
@Serializable
data class ClassCalendarDay(
    val date: LocalDate,
    val lessonId: String? = null,
    /** The lesson's title, so a day cell can name what is on it without a request per day. */
    val title: String? = null,
    val status: WeekLessonStatus? = null,
    val type: LessonType? = null,
    val playedCount: Int = 0,
    val schoolDay: Boolean = true,
    val gap: Boolean = false,
)

/**
 * `GET /teacher/classes/{classId}/calendar?month=yyyy-MM` (§6 screen 12, `docs/teacher-flow.md` §7): the month,
 * with the gaps flagged. `?year=&month=<number>` is the P4.0 shape and keeps working.
 *
 * The header fields describe the section itself — [className] is `1A`, and [subject] is the subject the caller
 * teaches in it, because a section since V7 carries its subjects on its teaching assignments rather than on the row
 * (N2.3b). [subject] is null only for a section nobody teaches, which no teacher can reach.
 */
@Serializable
data class ClassCalendar(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject? = null,
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
 * [classId] and [className] are the section she sits in, which is the section in the path and no other: the list is
 * the roster of `1A`, never every child of British Grade 1 (N2.3b). They travel with the row so the dashboard can
 * label a child without a second lookup.
 *
 * [levelReached] is the highest level she has *completed* of any lesson of the class's school, 0 when none;
 * [weakSkills] are the `ProgressBands.NEEDS_ANOTHER_LOOK` bands, words rather than percentages, as everywhere else.
 */
@Serializable
data class ClassStudent(
    val childId: String,
    val name: String,
    val classId: String,
    val className: String,
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

// ---------------------------------------------------------------------------------------------------------------
// N2.1 — This week, My classes and her lessons (`docs/teacher-flow.md` §4, §7, §8)
// ---------------------------------------------------------------------------------------------------------------

/**
 * What a cell of the week grid says about a lesson, which is coarser than [quest.api.dto.LessonStatus] on purpose:
 * §4 gives the teacher three words. `draft` is everything still being made (created, uploading, analyzing, needs
 * review, error, paused), `ready` is a lesson in review waiting to be published, `published` is live.
 */
@Serializable
enum class WeekLessonStatus {
    @SerialName("none") NONE, @SerialName("draft") DRAFT, @SerialName("ready") READY, @SerialName("published") PUBLISHED
}

/** Homework, or an exam (N4.3 fills the second; every lesson written today is [HOMEWORK]). */
@Serializable
enum class LessonType { @SerialName("homework") HOMEWORK, @SerialName("exam") EXAM }

/**
 * The lesson in one cell. [playedCount] is how many children of that class have answered at least one stop of it and
 * [childrenCount] how many sit in the class, so §4's "12/24 played" needs no second request.
 */
@Serializable
data class WeekLesson(
    val id: String,
    val title: String? = null,
    val status: WeekLessonStatus = WeekLessonStatus.NONE,
    val type: LessonType = LessonType.HOMEWORK,
    val playedCount: Int = 0,
    val childrenCount: Int = 0,
    val version: Int = 0,
)

/** The exam window a cell falls in (N4.3). Always null today; the field exists so the grid is not reshaped later. */
@Serializable
data class WeekExam(val lessonId: String, val opensAt: LocalDate? = null, val closesAt: LocalDate? = null)

@Serializable
data class WeekCell(val date: LocalDate, val lesson: WeekLesson? = null, val exam: WeekExam? = null)

/** One row of the grid: one teaching assignment, one cell per day of the school week. */
@Serializable
data class WeekRow(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val cells: List<WeekCell> = emptyList(),
)

/** A school day of one of her classes with nothing dated on it, named so the strip can say "1B · Tuesday". */
@Serializable
data class WeekGap(val classId: String, val className: String, val date: LocalDate)

/** The strip under the grid. [examsClosing] and [marksWaiting] stay empty until N4; the shape is fixed now. */
@Serializable
data class WeekSummary(
    val gaps: List<WeekGap> = emptyList(),
    val examsClosing: List<WeekGap> = emptyList(),
    val marksWaiting: Int = 0,
)

/**
 * `GET /teacher/week?start=YYYY-MM-DD` (§4 steps 2–8): every assignment she holds against the school week, in one
 * response and a fixed number of statements.
 *
 * [start] is the request's `start` snapped back to the first day of the school week in the school's timezone, and
 * [days] are the school's teaching days — Sunday–Thursday unless Platform settings or the school says otherwise, so
 * nothing on the client may assume a length or a first day.
 *
 * [today] is today in the school's timezone when the week on screen contains it, and null when she has paged
 * elsewhere. On a Friday or a Saturday today is not a teaching day at all, so it is appended to [days] as an extra
 * column and listed in [weekendDays] — otherwise a lesson published that morning has nowhere to appear. Every row
 * still has one cell per entry in [days], which is the only rule the grid needs; [weekendDays] is there so a client
 * that wants to can tint the column, and a client that ignores it is still correct.
 */
@Serializable
data class TeacherWeek(
    val start: LocalDate,
    val days: List<LocalDate> = emptyList(),
    val rows: List<WeekRow> = emptyList(),
    val summary: WeekSummary = WeekSummary(),
    val today: LocalDate? = null,
    val weekendDays: List<LocalDate> = emptyList(),
)

/** `GET /teacher/classes` (§7): one card per assignment, with today's lesson and how much of the class has played. */
@Serializable
data class TeacherClassCard(
    val classId: String,
    val className: String,
    val curriculum: Curriculum,
    val grade: Int,
    val subject: Subject,
    val todayLessonId: String? = null,
    val todayStatus: WeekLessonStatus = WeekLessonStatus.NONE,
    val childrenCount: Int = 0,
    val playedToday: Int = 0,
)

/**
 * `POST /teacher/lessons` (§8): the class and the subject must be a teaching assignment she holds, or the server
 * answers the same 403 it gives for a class of another teacher — the chooser never decides what she may write.
 */
@Serializable
data class CreateTeacherLessonRequest(
    val classId: String,
    val subject: Subject,
    val date: LocalDate,
    val source: LessonSource,
    val title: String? = null,
    val notes: String? = null,
    val practiceLength: Int = 7,
)

/** `PATCH /teacher/lessons/{id}`: moving a lesson to another day. 409 once it is published — unpublish it first. */
@Serializable
data class MoveLessonRequest(val date: LocalDate)

/** `POST /teacher/lessons/{id}/copy`: a full copy into another class of the same grade and subject that she teaches. */
@Serializable
data class CopyLessonRequest(val classId: String)

/**
 * `POST /teacher/lessons/{id}/publish`: the complete set of classes this lesson should be live in. The lesson's own
 * class is **not** implied — include it to publish it, leave it out to publish only the copies.
 */
@Serializable
data class PublishToClassesRequest(val classIds: List<String> = emptyList())

/** One result of a publish: the class, the lesson row that is live in it, and the version that publish produced. */
@Serializable
data class PublishedCopy(val classId: String, val lessonId: String, val version: Int)
