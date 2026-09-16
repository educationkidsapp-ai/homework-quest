package quest.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.SchoolTheme
import quest.api.dto.UpdateChildRequest

/**
 * Everything the mobile app asks the backend. `FakeContentApi` (seeded, in-app) and `RemoteContentApi`
 * (Ktor → Spring Boot) implement it; no screen may know which is behind it. The app never uploads slides
 * and never triggers AI — it only downloads published lessons and reports the child's answers.
 */
interface ContentApi {
    suspend fun listChildren(): List<Child>
    suspend fun createChild(request: CreateChildRequest): Child
    suspend fun updateChild(id: String, request: UpdateChildRequest): Child
    suspend fun deleteChild(id: String)

    /** `GET /children/{id}/map?from=&to=` — assembled by the §7 rule. */
    suspend fun map(childId: String, from: LocalDate, to: LocalDate): MapResponse

    /** `GET /lessons/{id}` — immutable per version; safe to cache forever. */
    suspend fun lesson(id: String, version: Int? = null): PublishedLesson

    suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck
    suspend fun uploadStopMedia(childId: String, stopId: String, media: UploadFile, kind: MediaKind): MediaRef
    suspend fun progress(childId: String): ProgressResponse

    /**
     * `GET /schools/{id}/flags` — public, cached, ETagged; the app calls it on launch and every six hours (§4).
     * All 14 keys, with the value this school sees.
     *
     * Both this and [schoolTheme] have a default body returning the platform defaults, so an implementation that
     * predates P2.2 — `FakeContentApi`, and any test double — still compiles and behaves as an unthemed school with
     * every shipped feature on. `RemoteContentApi` overrides them.
     */
    suspend fun schoolFlags(schoolId: String): Map<String, Boolean> = DEFAULT_FLAGS

    /** `GET /schools/{id}/theme` — public, cached, ETagged (§3). */
    suspend fun schoolTheme(schoolId: String): SchoolTheme = SchoolTheme()
}

/**
 * What [ContentApi.schoolFlags] answers without a backend: the `default_on` of the 14 §4 flags as
 * `V5__flags_themes.sql` seeds them — on for what ships today, off for what is not built yet.
 */
val DEFAULT_FLAGS: Map<String, Boolean> = mapOf(
    "lessons.pdf" to true,
    "lessons.slides" to true,
    "lessons.images" to true,
    "lessons.manual" to true,
    "levels.three" to true,
    "retell.recording" to true,
    "openAnswer.drawing" to true,
    "parentPanel.arabic" to true,
    "complaints" to false,
    "announcements" to false,
    "teacherQuestions" to false,
    "stickers.treasureChest" to true,
    "progress.weeklyEmail" to false,
    "certificates" to true,
)

/** Firebase Authentication on the app (expect/actual), `FakeAuth` while developing. */
interface AuthProvider {
    val state: StateFlow<AuthState>
    suspend fun signIn(email: String, password: String)
    suspend fun register(email: String, password: String)
    suspend fun signInWithGoogle()
    suspend fun signOut()
    /** The Firebase ID token the app attaches to every request; null when signed out. */
    suspend fun idToken(forceRefresh: Boolean = false): String?
}

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val uid: String, val email: String) : AuthState
}

class UploadFile(val fileName: String, val mimeType: String, val bytes: ByteArray)

class ApiException(val error: ApiError, cause: Throwable? = null) : Exception("${error.code}: ${error.message}", cause)
