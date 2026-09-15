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
    suspend fun cache(): List<CacheEntry>
    suspend fun usage(): UsageResponse
    suspend fun calendar(curriculum: Curriculum, grade: Int, year: Int, month: Int): CalendarResponse
}

@Serializable data class AdminSession(val token: String, val email: String, val expiresAt: Long)
@Serializable data class JobRef(val jobId: String, val status: LessonStatus)
@Serializable data class LessonFilter(val curriculum: Curriculum? = null, val grade: Int? = null, val subject: Subject? = null, val from: LocalDate? = null, val to: LocalDate? = null)
/** Where a lesson's content came from. Uploads are classified from their files; `manual` lessons are written in the panel. */
@Serializable enum class LessonSource { @SerialName("pdf") PDF, @SerialName("slides") SLIDES, @SerialName("images") IMAGES, @SerialName("manual") MANUAL }
@Serializable data class CreateLessonRequest(val curriculum: Curriculum, val grade: Int, val subject: Subject, val date: LocalDate, val notes: String? = null, val practiceLength: Int = 7, val source: LessonSource? = null, val title: String? = null)
@Serializable data class LessonImage(val id: String, val url: String)
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
    /** Page images and admin-attached pictures (`Stop.imageId` → url); full lesson only. */
    val images: List<quest.api.dto.PageImage> = emptyList(),
)

@Serializable data class CacheEntry(val fileHash: String, val curriculum: Curriculum, val grade: Int, val subject: Subject, val promptVersion: String, val tokenUsage: Long, val createdAt: Long, val hits: Int, val lessonIds: List<String>)
@Serializable data class CourseUsage(val course: Course, val children: Int)
@Serializable data class LessonUsage(val lessonId: String, val title: String, val course: Course, val date: LocalDate, val played: Int, val completed: Int)
@Serializable data class StopAccuracy(val lessonId: String, val stopId: String, val title: String, val type: String, val attempts: Int, val firstTryCorrect: Int)
@Serializable data class UsageResponse(val courses: List<CourseUsage>, val lessons: List<LessonUsage>, val stops: List<StopAccuracy>)
@Serializable data class CalendarDayInfo(val date: LocalDate, val math: Boolean, val english: Boolean)
@Serializable data class CalendarResponse(val course: Course, val year: Int, val month: Int, val days: List<CalendarDayInfo>)
