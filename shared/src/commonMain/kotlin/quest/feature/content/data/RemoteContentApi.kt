package quest.feature.content.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.encodeURLPathPart
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.fromHttpToGmtDate
import quest.core.platform.ServerClock
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.UploadFile
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.Child
import quest.api.dto.ChildAttendanceRecord
import quest.api.dto.ChildAttendanceResponse
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.CreateChildRequest
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.PlatformSettings
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.SchoolTheme
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UpdateChildRequest
import quest.core.json.AppJson
import quest.feature.content.domain.SchoolApi
import quest.feature.content.domain.ThemeFetch

/** The real implementation: Spring Boot API over HTTP with the Firebase ID token on every request. */
class RemoteContentApi(private val baseUrl: String, private val auth: AuthProvider, engineClient: HttpClient) : ContentApi, SchoolApi {
    private val client = engineClient.config {
        // D16: the app decodes with [AppJson] (`ignoreUnknownKeys = true`), never with the strict validator Json.
        // A field the server adds after this binary shipped is then ignored instead of throwing on the first body.
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    private suspend fun HttpRequestBuilder.authed() = authed(auth)

    override suspend fun listChildren(): List<Child> = call { client.get("$baseUrl/children") { authed() } }
    override suspend fun createChild(request: CreateChildRequest): Child = call { client.post("$baseUrl/children") { authed(); contentType(ContentType.Application.Json); setBody(request) } }
    override suspend fun updateChild(id: String, request: UpdateChildRequest): Child = call { client.patch("$baseUrl/children/$id") { authed(); contentType(ContentType.Application.Json); setBody(request) } }
    override suspend fun deleteChild(id: String) { val r = client.delete("$baseUrl/children/$id") { authed() }; if (!r.status.isSuccess()) throw r.toException() }
    override suspend fun map(childId: String, from: LocalDate, to: LocalDate): MapResponse = call { client.get("$baseUrl/children/$childId/map") { authed(); parameter("from", from.toString()); parameter("to", to.toString()) } }
    override suspend fun lesson(id: String, version: Int?): PublishedLesson = call { client.get("$baseUrl/lessons/$id") { authed(); if (version != null) parameter("version", version) } }
    override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck = call { client.post("$baseUrl/children/$childId/attempts") { authed(); contentType(ContentType.Application.Json); setBody(attempts) } }
    override suspend fun uploadStopMedia(childId: String, stopId: String, media: UploadFile, kind: MediaKind): MediaRef = call {
        client.post("$baseUrl/children/$childId/stops/$stopId/media") {
            authed()
            setBody(MultiPartFormDataContent(formData {
                append("kind", kind.name.lowercase())
                append("file", media.bytes, Headers.build { append(HttpHeaders.ContentType, media.mimeType); append(HttpHeaders.ContentDisposition, "filename=\"${media.fileName}\"") })
            }))
        }
    }
    override suspend fun progress(childId: String): ProgressResponse = call { client.get("$baseUrl/children/$childId/progress") { authed() } }

    // ---- §3 theme, §4 flags, §A platform settings. All three routes are public: no bearer token, because the app
    // reads them before anyone has signed in.
    override suspend fun schoolFlags(schoolId: String): Map<String, Boolean> = call { client.get("$baseUrl/schools/$schoolId/flags") }

    override suspend fun schoolTheme(schoolId: String): SchoolTheme = call { client.get("$baseUrl/schools/$schoolId/theme") }

    override suspend fun schoolTheme(schoolId: String, ifNoneMatch: String?): ThemeFetch {
        val response = try {
            client.get("$baseUrl/schools/$schoolId/theme") { if (!ifNoneMatch.isNullOrBlank()) header(HttpHeaders.IfNoneMatch, ifNoneMatch) }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e)
        }
        if (response.status == HttpStatusCode.NotModified) return ThemeFetch(theme = null, etag = ifNoneMatch, notModified = true)
        if (!response.status.isSuccess()) throw response.toException()
        return ThemeFetch(response.body(), response.headers[HttpHeaders.ETag] ?: ifNoneMatch)
    }

    override suspend fun platformSettings(): PlatformSettings = call { client.get("$baseUrl/platform-settings") }

    // ---- C1: chat with the child's teachers (`docs/runbook.md` "Chat")
    override suspend fun chatThreads(childId: String): List<ChatThread> =
        call { client.get("$baseUrl/children/$childId/chat/threads") { authed() } }

    override suspend fun parentCoordinators(childId: String): List<ChatThread> =
        call { client.get("$baseUrl/children/$childId/coordinators") { authed() } }

    override suspend fun childManagers(childId: String): List<ChatThread> =
        call { client.get("$baseUrl/children/$childId/managers") { authed() } }

    // ---- RM4: the parent's broadcasts feed (`docs/runbook.md` "Broadcasts"). Behind the `announcements` flag.
    override suspend fun childBroadcasts(childId: String): BroadcastFeed =
        call { client.get("$baseUrl/children/$childId/broadcasts") { authed() } }

    override suspend fun markBroadcastRead(childId: String, broadcastId: String): BroadcastView =
        call { client.post("$baseUrl/children/$childId/broadcasts/$broadcastId/read") { authed() } }

    // ---- B3: the parent's own notification rows — the same `/me/notifications` routes as the dashboard bell.
    override suspend fun notifications(unread: Boolean?, limit: Int?): List<quest.api.dto.NotificationView> =
        call { client.get("$baseUrl/me/notifications") { authed(); unread?.let { parameter("unread", it) }; limit?.let { parameter("limit", it) } } }

    override suspend fun unreadNotificationCount(): quest.api.dto.UnreadCount = call { client.get("$baseUrl/me/notifications/unread-count") { authed() } }

    override suspend fun markNotificationRead(id: String): quest.api.dto.NotificationView = call { client.post("$baseUrl/me/notifications/$id/read") { authed() } }

    override suspend fun markAllNotificationsRead(): quest.api.dto.UnreadCount = call { client.post("$baseUrl/me/notifications/read-all") { authed() } }

    // ---- B4: the parent's push token (`docs/runbook.md` "Push notifications"); both answer 204.
    override suspend fun registerDevice(request: quest.api.dto.RegisterDeviceRequest) = noContent {
        client.post("$baseUrl/me/devices") { authed(); contentType(ContentType.Application.Json); setBody(request) }
    }

    override suspend fun unregisterDevice(token: String) = noContent { client.delete("$baseUrl/me/devices/${token.encodeURLPathPart()}") { authed() } }

    // ---- MH1/MH3: the weekly-plan archive and the parent's own account
    override suspend fun childWeeklyPlans(childId: String, from: String?, to: String?): quest.api.dto.WeeklyPlanArchive =
        call {
            client.get("$baseUrl/children/$childId/weekly-plans") {
                authed()
                if (!from.isNullOrBlank()) parameter("from", from)
                if (!to.isNullOrBlank()) parameter("to", to)
            }
        }

    override suspend fun parentProfile(): quest.api.dashboard.ParentProfile = call { client.get("$baseUrl/parent/me") { authed() } }

    override suspend fun updateParentProfile(phone: String?): quest.api.dashboard.ParentProfile =
        call {
            client.patch("$baseUrl/parent/me") {
                authed(); contentType(ContentType.Application.Json); setBody(quest.api.dashboard.UpdateParentRequest(phone))
            }
        }

    override suspend fun chatMessages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> =
        call {
            client.get("$baseUrl/children/$childId/chat/threads/$teacherId/messages") {
                authed()
                if (!before.isNullOrBlank()) parameter("before", before)
                if (!since.isNullOrBlank()) parameter("since", since)
                if (limit != null) parameter("limit", limit)
            }
        }

    override suspend fun sendChatMessage(childId: String, teacherId: String, request: SendChatMessageRequest): ChatMessage =
        call {
            client.post("$baseUrl/children/$childId/chat/threads/$teacherId/messages") {
                authed()
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun markChatRead(childId: String, teacherId: String): ChatReadReceipt =
        call { client.post("$baseUrl/children/$childId/chat/threads/$teacherId/read") { authed() } }

    override suspend fun childAttendance(childId: String, from: String?, to: String?): ChildAttendanceResponse =
        call {
            client.get("$baseUrl/children/$childId/attendance") {
                authed()
                if (!from.isNullOrBlank()) parameter("from", from)
                if (!to.isNullOrBlank()) parameter("to", to)
            }
        }

    override suspend fun todayAttendance(childId: String): ChildAttendanceRecord? =
        try {
            val response = client.get("$baseUrl/children/$childId/attendance/today") { authed() }
            if (response.status == HttpStatusCode.NoContent || !response.status.isSuccess()) {
                null
            } else {
                val text = response.bodyAsText().trim()
                if (text.isEmpty() || text == "null") null else AppJson.decodeFromString(ChildAttendanceRecord.serializer(), text)
            }
        } catch (_: Exception) {
            null
        }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) { throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e) }
        // M4 (D8): every answer carries the server's time; the exam window is judged against it, not the tablet's clock.
        response.headers[HttpHeaders.Date]?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }?.let { ServerClock.observe(it) }
        if (!response.status.isSuccess()) throw response.toException()
        return response.body()
    }

    private suspend inline fun noContent(block: () -> HttpResponse) {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) { throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e) }
        if (!response.status.isSuccess()) throw response.toException()
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val text = bodyAsText()
        val error = runCatching { AppJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(when (status.value) { 401 -> ApiError.UNAUTHORIZED; 403 -> ApiError.FORBIDDEN; 404 -> ApiError.NOT_FOUND; else -> ApiError.NETWORK }, "Server said ${status.value}")
        return ApiException(error)
    }
}
