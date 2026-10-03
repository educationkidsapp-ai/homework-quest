package quest.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.Child
import quest.api.dto.ChildAttendanceRecord
import quest.api.dto.ChildAttendanceResponse
import quest.api.dto.CreateChildRequest
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.ProgressResponse
import quest.api.dashboard.ParentAnnouncement
import quest.api.dashboard.TeacherAnswerUpload
import quest.api.dashboard.TeacherQuestionPlay
import quest.api.dto.PublishedLesson
import quest.api.dto.SchoolTheme
import quest.api.dto.SendChatMessageRequest
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

    // ---- P4.0: the teacher's question island and the announcements card (§6 "Mobile app additions")

    /**
     * `GET /children/{id}/teacher-questions/{questionId}` — the stops behind a `MapResponse.teacherIslands` entry,
     * shaped like a `Play` so the existing stop player can run them ([TeacherQuestionPlay.asPlay]).
     *
     * Behind the `teacherQuestions` flag: 404 while it is off for the child's school, which is indistinguishable
     * from a question that does not exist. The island is only ever on the map when the flag is on, so the app
     * reaches this route only for questions it was told about.
     */
    suspend fun teacherQuestion(childId: String, questionId: String): TeacherQuestionPlay =
        throw NotImplementedError("teacherQuestion needs a backend")

    /**
     * `POST /children/{id}/teacher-questions/{questionId}/answers` — a batch, like [uploadAttempts], idempotent on
     * (question, child, stop). Answers the number of rows it accepted.
     */
    suspend fun uploadTeacherAnswers(childId: String, questionId: String, answers: List<TeacherAnswerUpload>): AttemptAck =
        AttemptAck(0)

    /**
     * `GET /children/{id}/announcements` — the live notes from the teachers of the child's classes, newest first.
     * Behind the `announcements` flag; 404 while it is off, and an empty list when the school has posted none.
     *
     * Defaulted to empty so `FakeContentApi` and every test double keep compiling: an app with no backend simply
     * shows no announcements card.
     */
    suspend fun announcements(childId: String): List<ParentAnnouncement> = emptyList()

    // ---- C1: chat with the child's teachers (`docs/runbook.md` "Chat"). Behind the `chat` flag: 404 while off.

    /** `GET /children/{id}/chat/threads` — one row per teacher of the child's section; `409 child_not_placed` while she has none. */
    suspend fun chatThreads(childId: String): List<ChatThread> = throw NotImplementedError("chatThreads needs a backend")

    /**
     * `GET /children/{id}/coordinators` (R4, DR3) — the coordinators whose scope covers the subjects taught in the
     * child's section, as [ChatThread] rows: `id` is null until the parent writes the first message, exactly as an
     * unwritten teacher row is, and `subject` names what each coordinator holds. A coordinator is not one of the
     * child's teachers, so she is absent from [chatThreads] until a thread exists — this is where one is started.
     *
     * Behind the `chat` flag (404 while off) and defaulted to empty so `FakeContentApi` and every test double keep
     * compiling: an app with no backend simply offers no coordinator to write to.
     */
    suspend fun parentCoordinators(childId: String): List<ChatThread> = emptyList()

    /**
     * `GET /children/{id}/chat/threads/{teacherId}/messages?before=&since=&limit=` — a page, oldest first. `before` is
     * a message id and pages backwards from it (the default page is the newest); `since` is a message id and answers
     * everything after it, which is how a client fills the gap after a socket reconnect.
     */
    suspend fun chatMessages(childId: String, teacherId: String, before: String? = null, since: String? = null, limit: Int? = null): List<ChatMessage> =
        throw NotImplementedError("chatMessages needs a backend")

    /** `POST /children/{id}/chat/threads/{teacherId}/messages` — 1–2000 characters of plain text; `429 rate_limited` past 30 a minute. */
    suspend fun sendChatMessage(childId: String, teacherId: String, request: SendChatMessageRequest): ChatMessage =
        throw NotImplementedError("sendChatMessage needs a backend")

    /** `POST /children/{id}/chat/threads/{teacherId}/read` — everything the teacher wrote is read. */
    suspend fun markChatRead(childId: String, teacherId: String): ChatReadReceipt = throw NotImplementedError("markChatRead needs a backend")

    /**
     * `GET /children/{id}/managers` — RM2 (DR5): the manager of the department her child's section is in, as thread
     * rows, so she can write to her about the school, the child or a coordinator. `topic = complaint` is allowed on
     * this thread as it is on a coordinator's. Behind the `chat` flag.
     */
    suspend fun childManagers(childId: String): List<ChatThread> = throw NotImplementedError("childManagers needs a backend")

    /**
     * MH1 `GET /parent/me` — her own account: the address she signed in with and the mobile number the school has for
     * her. The default is an empty profile so a build against an older server still runs.
     */
    suspend fun parentProfile(): quest.api.dashboard.ParentProfile =
        throw NotImplementedError("parentProfile needs a backend")

    /**
     * MH1 `PATCH /parent/me` — her mobile number, and nothing else (the address is her Firebase identity). An empty
     * string clears it. It is what the department manager reaches her on from the Children directory.
     */
    suspend fun updateParentProfile(phone: String?): quest.api.dashboard.ParentProfile =
        throw NotImplementedError("updateParentProfile needs a backend")

    // ---- RM2: broadcasts (`docs/runbook.md` "Broadcasts"). Behind the `announcements` flag: 404 while off.

    /**
     * `GET /children/{id}/broadcasts` — the weekly plans, announcements and events sent to the parents of this child's
     * section, newest first, with the unread count for that child. Named by child like every other flagged parent
     * route: the school whose `announcements` flag decides it is the child's own.
     */
    suspend fun childBroadcasts(childId: String): BroadcastFeed = BroadcastFeed()

    /**
     * MG1 `GET /children/{id}/weekly-plans?from&to` — the weekly plans sent to this child's section over a window,
     * newest week first, **past weeks included**: the feed above may hide an expired row and the archive never does.
     * Absent dates mean the last twelve weeks.
     */
    suspend fun childWeeklyPlans(childId: String, from: String? = null, to: String? = null): quest.api.dto.WeeklyPlanArchive =
        quest.api.dto.WeeklyPlanArchive(from ?: "", to ?: "")

    /** `POST /children/{id}/broadcasts/{broadcastId}/read` — this one is read; the row comes back with `read = true`. */
    suspend fun markBroadcastRead(childId: String, broadcastId: String): BroadcastView =
        throw NotImplementedError("markBroadcastRead needs a backend")

    /** `GET /children/{id}/attendance?from=&to=` — child attendance history & summary. */
    suspend fun childAttendance(childId: String, from: String? = null, to: String? = null): ChildAttendanceResponse =
        ChildAttendanceResponse()

    /** `GET /children/{id}/attendance/today` — child today's attendance record. */
    suspend fun todayAttendance(childId: String): ChildAttendanceRecord? = null

    // ---- B3: the parent's own notifications (`docs/runbook.md` "Notifications"). The same four `/me/notifications`
    // routes the dashboard bell uses, answering the signed-in parent's rows: `chat.message` (one unread per thread,
    // read when she opens it), `exam.released` and `homework.published`, each naming the child in `childId`.
    // Broadcasts stay on `childBroadcasts`. The live half is the `notification` frame on `/ws/chat`.

    /** `GET /me/notifications?unread=&limit=` — newest first; `limit` 1–100 (default 20). */
    suspend fun notifications(unread: Boolean? = null, limit: Int? = null): List<quest.api.dto.NotificationView> = emptyList()

    /** `GET /me/notifications/unread-count` — the badge. */
    suspend fun unreadNotificationCount(): quest.api.dto.UnreadCount = quest.api.dto.UnreadCount(0)

    /** `POST /me/notifications/{id}/read` — one row read (idempotent); 404 when it is not hers. */
    suspend fun markNotificationRead(id: String): quest.api.dto.NotificationView =
        throw NotImplementedError("markNotificationRead needs a backend")

    /** `POST /me/notifications/read-all` — every unread row read; answers the new count (0). */
    suspend fun markAllNotificationsRead(): quest.api.dto.UnreadCount = quest.api.dto.UnreadCount(0)

    // ---- B4: push (`docs/runbook.md` "Push notifications"). The FCM data map the app receives is
    // `quest.api.dto.PushMessage`; every push is also a `/me/notifications` row. Both default to doing nothing: a fake
    // or older backend sends no push.

    /** `POST /me/devices` — register (or refresh) this phone's FCM token for the signed-in parent; 204. */
    suspend fun registerDevice(request: quest.api.dto.RegisterDeviceRequest) {}

    /** `POST /me/devices/unregister {token}` — on sign-out; 204, and 204 again for a token she does not hold. */
    suspend fun unregisterDevice(token: String) {}
}

/**
 * What [ContentApi.schoolFlags] answers without a backend: the `default_on` of every flag as
 * `V5__flags_themes.sql` and `V7__sections.sql` seed them — on for what ships today, off for what is not built yet.
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
    // N1.1, the one-school build (`docs/prompts/dashboard-first-one-school.md`). All off: `multiSchool` hides the
    // Admin school screens and the switcher while there is one school, and the rest gate what N2–N4 build.
    "multiSchool" to false,
    "webPlayer" to false,
    "gradebook" to false,
    "openStopMarking" to false,
    "exams" to false,
    "teacher.rosterEdit" to false,
    "join.byList" to false,
    // C1/C3: parent ↔ teacher chat (REST + `/ws/chat`).
    "chat" to false,
)

/** Firebase Authentication on the app (expect/actual), `FakeAuth` while developing. */
interface AuthProvider {
    val state: StateFlow<AuthState>
    suspend fun signIn(email: String, password: String)
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
