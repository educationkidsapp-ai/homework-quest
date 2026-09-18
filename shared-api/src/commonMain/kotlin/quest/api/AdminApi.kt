package quest.api

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import quest.api.dto.ApiError
import quest.api.dto.Course
import quest.api.dto.Curriculum
import quest.api.dto.ExtractedSkill
import quest.api.dto.LessonStatus
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.SourceAnalysis
import quest.api.dto.Stop
import quest.api.dto.Subject

/** The admin panel's view of the backend (JWT). Uploads and AI live only here. */
interface AdminApi {
    suspend fun signIn(email: String, password: String): AdminSession
    suspend fun lessons(filter: LessonFilter): List<AdminLesson>
    suspend fun createLesson(request: CreateLessonRequest): AdminLesson
    suspend fun uploadFiles(lessonId: String, files: List<UploadFile>): JobRef
    suspend fun lesson(lessonId: String): AdminLesson
    suspend fun analyze(lessonId: String): JobRef
    suspend fun confirmSkills(lessonId: String, skills: List<ConfirmedSkill>): JobRef
    suspend fun updateStop(stopId: String, stop: Stop): Stop
    // ---- manual authoring (source = manual, or hand-edits to any lesson in review)
    /** Creates an empty play for a level/variant (manual lessons); the admin fills it stop by stop. */
    suspend fun createPlay(lessonId: String, level: Int, variant: Int = 0): AdminPlay
    suspend fun addStop(playId: String, stop: Stop): Stop
    suspend fun deleteStop(stopId: String)
    /** New order of the play's stop ids (a permutation of the current ones). */
    suspend fun reorderStops(playId: String, stopIds: List<String>): Play
    /** Uploads a picture into the lesson's image folder; set the returned id as a stop's `imageId`. */
    suspend fun uploadImage(lessonId: String, file: UploadFile): LessonImage
    /** Prompt A + B on a short lesson text: writes the levels the admin has not written (typically 2, 3 and the Again variant) and the parent panel. */
    suspend fun generateFromText(lessonId: String, text: String): JobRef
    suspend fun regenerateStop(stopId: String): Stop
    suspend fun regeneratePlay(playId: String): Play
    suspend fun updateParentPanel(lessonId: String, panel: ParentPanel): ParentPanel
    suspend fun publish(lessonId: String): AdminLesson
    suspend fun unpublish(lessonId: String): AdminLesson
    suspend fun deleteFiles(lessonId: String)
    /** Removes a lesson that is not published (its files, plays, stops, panel); the AI caches stay. */
    suspend fun deleteLesson(lessonId: String)
    /** Removes every lesson in error. Returns how many were deleted. */
    suspend fun deleteFailedLessons(): Int
    /** Re-runs from the first step in error (or the first pending one) and continues through the rest. */
    suspend fun retry(lessonId: String): JobRef
    /** Re-runs one step only. */
    suspend fun retryStep(lessonId: String, step: PipelineStep): JobRef
    suspend fun cache(): List<CacheEntry>
    suspend fun usage(): UsageResponse
    suspend fun calendar(curriculum: Curriculum, grade: Int, year: Int, month: Int): CalendarResponse
}

/** `webAdmin/`'s view of the sign-in response; the dashboard reads the same payload as `quest.api.dashboard.SignInResponse`. */
@Serializable data class AdminSession(
    val token: String, val email: String, val expiresAt: Long,
    val role: quest.api.dashboard.Role = quest.api.dashboard.Role.ADMIN, val schoolId: String? = null,
    val displayName: String? = null, val mustChangePassword: Boolean = false,
)
@Serializable data class JobRef(val jobId: String, val status: LessonStatus)
/**
 * `GET /admin/lessons` filters; an absent field does not filter. [schoolId] is the Admin's All-lessons school column
 * (§6 screen 8) and is ignored for a scoped caller in the sense that matters: her rows are already filtered to her
 * own school, so naming another one answers an empty list rather than that school's lessons.
 *
 * [classId] is §6 screen 12's "her lessons only, by class": it narrows to one Class, and a class of another school
 * narrows to nothing for the same reason [schoolId] does.
 */
@Serializable data class LessonFilter(val curriculum: Curriculum? = null, val grade: Int? = null, val subject: Subject? = null, val from: LocalDate? = null, val to: LocalDate? = null, val schoolId: String? = null, val classId: String? = null)
/** Where a lesson's content came from. Uploads are classified from their files; `manual` lessons are written in the panel. */
@Serializable enum class LessonSource { @SerialName("pdf") PDF, @SerialName("slides") SLIDES, @SerialName("images") IMAGES, @SerialName("manual") MANUAL }
/**
 * A new lesson. [classId] is the section it goes into (V7, D14) and is what the dashboard sends; when it is absent
 * the server resolves the section itself — an ADMIN gets the school's section for that (curriculum, grade), and a
 * TEACHER the one class her teaching assignments name for that subject, or 403 when she teaches none.
 */
@Serializable data class CreateLessonRequest(val curriculum: Curriculum, val grade: Int, val subject: Subject, val date: LocalDate, val notes: String? = null, val practiceLength: Int = 7, val source: LessonSource? = null, val title: String? = null, val classId: String? = null)
@Serializable data class LessonImage(val id: String, val url: String)

/** The pipeline every uploaded lesson goes through; each step is idempotent and cache-first, so a retry resumes where it failed. */
@Serializable enum class PipelineStep {
    @SerialName("upload") UPLOAD, @SerialName("analyze") ANALYZE, @SerialName("skills") SKILLS,
    @SerialName("generate_L1") GENERATE_L1, @SerialName("generate_L2") GENERATE_L2, @SerialName("generate_L3") GENERATE_L3,
    @SerialName("generate_again") GENERATE_AGAIN, @SerialName("panel") PANEL;
    val label: String get() = when (this) { UPLOAD -> "Upload"; ANALYZE -> "Read the pages"; SKILLS -> "Confirm skills"; GENERATE_L1 -> "Level 1"; GENERATE_L2 -> "Level 2"; GENERATE_L3 -> "Level 3"; GENERATE_AGAIN -> "Again variant"; PANEL -> "Parent panel" }
    val short: String get() = when (this) { UPLOAD -> "upload"; ANALYZE -> "analyze"; SKILLS -> "skills"; GENERATE_L1 -> "generate L1"; GENERATE_L2 -> "generate L2"; GENERATE_L3 -> "generate L3"; GENERATE_AGAIN -> "generate Again"; PANEL -> "parent panel" }
}
@Serializable enum class StepStatus { @SerialName("pending") PENDING, @SerialName("running") RUNNING, @SerialName("done") DONE, @SerialName("error") ERROR }
@Serializable data class LessonStepInfo(val step: PipelineStep, val status: StepStatus, val attempt: Int = 0, val errorCode: String? = null, val errorMessage: String? = null, val updatedAt: Long = 0)
@Serializable data class GenerateFromTextRequest(val text: String)
@Serializable data class ReorderRequest(val stopIds: List<String>)
@Serializable data class CreatePlayRequest(val level: Int, val variant: Int = 0)
@Serializable data class ConfirmedSkill(val id: String? = null, val name: String, val subject: Subject, val method: String? = null)
@Serializable data class SourceFileInfo(val id: String, val fileName: String, val fileHash: String, val pageCount: Int, val cacheHit: Boolean, val deleted: Boolean)
@Serializable data class AdminPlay(val id: String, val level: Int, val variant: Int, val play: Play, val promptVersion: String, val generatedAt: Long)

@Serializable
data class AdminLesson(
    val id: String, val course: Course, val subject: Subject, val date: LocalDate, val status: LessonStatus, val version: Int,
    val notes: String? = null, val title: String? = null, val tokenUsage: Long = 0, val tokensSaved: Long = 0,
    val files: List<SourceFileInfo> = emptyList(), val analysis: SourceAnalysis? = null, val skills: List<ExtractedSkill> = emptyList(),
    val plays: List<AdminPlay> = emptyList(), val parentPanel: ParentPanel? = null, val error: ApiError? = null,
    val publishedAt: Long? = null, val createdAt: Long = 0, val source: LessonSource = LessonSource.PDF,
    /** Pipeline progress (uploaded lessons; empty for manual ones) and the step a job is on right now. */
    val steps: List<LessonStepInfo> = emptyList(), val currentStep: PipelineStep? = null,
    /** Page images and admin-attached pictures (`Stop.imageId` → url); full lesson only. */
    val images: List<quest.api.dto.PageImage> = emptyList(),
    /** The school the lesson belongs to — the Admin's All-lessons school column (§6 screen 8). */
    val schoolId: String? = null,
    val schoolName: String? = null,
    /** N2.1: the section it was written for and who wrote it — the editor's fixed "1B · Maths · Ms Sara" header. */
    val classId: String? = null,
    val className: String? = null,
    val teacherId: String? = null,
    val teacherName: String? = null,
    /** Homework, or an exam (N4.3). */
    val type: quest.api.dashboard.LessonType = quest.api.dashboard.LessonType.HOMEWORK,
    /**
     * True when this lesson's source files were already in `analysis_cache` before it was created — the editor's
     * "Analyzed before · 0 tokens" badge. [tokenUsage] is what this lesson actually spent, which is 0 on a full
     * cache hit and on a copy; [tokensSaved] is what the cache avoided.
     */
    val analyzedBefore: Boolean = false,
)

@Serializable data class CacheEntry(val fileHash: String, val curriculum: Curriculum, val grade: Int, val subject: Subject, val promptVersion: String, val tokenUsage: Long, val createdAt: Long, val hits: Int, val lessonIds: List<String>)
@Serializable data class CourseUsage(val course: Course, val children: Int)
@Serializable data class LessonUsage(val lessonId: String, val title: String, val course: Course, val date: LocalDate, val played: Int, val completed: Int)
@Serializable data class StopAccuracy(val lessonId: String, val stopId: String, val title: String, val type: String, val attempts: Int, val firstTryCorrect: Int)
@Serializable data class UsageResponse(val courses: List<CourseUsage>, val lessons: List<LessonUsage>, val stops: List<StopAccuracy>)
@Serializable data class CalendarDayInfo(val date: LocalDate, val math: Boolean, val english: Boolean)
@Serializable data class CalendarResponse(val course: Course, val year: Int, val month: Int, val days: List<CalendarDayInfo>)
