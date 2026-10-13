package quest.feature.staff.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.InputProvider
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnregisterDeviceRequest
import quest.api.dto.UnreadCount
import quest.api.staff.StaffMe
import quest.core.json.AppJson
import quest.feature.chat.data.source
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.domain.StaffGateway

/**
 * The `/staff` facade over HTTP, plus the three routes that already existed and now take a staff token (the bell, the
 * device and the chat upload).
 *
 * **`X-School-Id`.** An ADMIN's token names no school, so the server resolves hers from that header, exactly as the
 * dashboard's requests do. It is sent whenever the session knows a school id and left off otherwise, which is the
 * only shape a non-Admin token ever needs.
 */
class RemoteStaffApi(
    private val baseUrl: String,
    private val auth: StaffAuth,
    engineClient: HttpClient,
) : StaffGateway {

    private val client = engineClient.config {
        install(ContentNegotiation) { json(AppJson) }
        install(HttpTimeout) { requestTimeoutMillis = 60_000 }
    }

    /** A school the session learned from `GET /staff/me` after an Admin's sign-in named none. */
    private var resolvedSchoolId: String? = null

    override suspend fun staffMe(): StaffMe =
        call<StaffMe> { client.get("$baseUrl/staff/me") { staffed() } }.also { resolvedSchoolId = it.schoolId }

    override suspend fun staffThreads(): List<ChatThread> = call { client.get("$baseUrl/staff/chat/threads") { staffed() } }

    override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> =
        call {
            client.get("$baseUrl/staff/chat/threads/$threadId/messages") {
                staffed()
                before?.let { parameter("before", it) }
                since?.let { parameter("since", it) }
                limit?.let { parameter("limit", it) }
            }
        }

    override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage =
        call { client.post("$baseUrl/staff/chat/threads/$threadId/messages") { staffed(); contentType(ContentType.Application.Json); setBody(request) } }

    override suspend fun staffRead(threadId: String): ChatReadReceipt =
        call { client.post("$baseUrl/staff/chat/threads/$threadId/read") { staffed() } }

    override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = call {
        client.post("$baseUrl/media/chat-attachments") {
            staffed()
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file", InputProvider(file.size) { file.source() },
                            Headers.build {
                                append(HttpHeaders.ContentType, file.contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "_")}\"")
                            },
                        )
                    },
                ),
            )
            onUpload { sent, total -> if (total != null && total > 0) onProgress((sent.toFloat() / total).coerceIn(0f, 1f)) }
        }
    }

    override suspend fun notifications(limit: Int): List<NotificationView> =
        call { client.get("$baseUrl/me/notifications") { staffed(); parameter("limit", limit) } }

    override suspend fun unreadNotificationCount(): UnreadCount = call { client.get("$baseUrl/me/notifications/unread-count") { staffed() } }

    override suspend fun markNotificationRead(id: String): NotificationView = call { client.post("$baseUrl/me/notifications/$id/read") { staffed() } }

    override suspend fun markAllNotificationsRead(): UnreadCount = call { client.post("$baseUrl/me/notifications/read-all") { staffed() } }

    override suspend fun registerDevice(request: RegisterDeviceRequest) = noContent {
        client.post("$baseUrl/me/devices") { staffed(); contentType(ContentType.Application.Json); setBody(request) }
    }

    override suspend fun unregisterDevice(token: String) = noContent {
        client.post("$baseUrl/me/devices/unregister") { staffed(); contentType(ContentType.Application.Json); setBody(UnregisterDeviceRequest(token)) }
    }

    private suspend fun HttpRequestBuilder.staffed() {
        auth.idToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        (auth.schoolId.value ?: resolvedSchoolId)?.let { header(SCHOOL_HEADER, it) }
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) {
            throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e)
        }
        if (!response.status.isSuccess()) throw response.toException()
        return response.body()
    }

    private suspend inline fun noContent(block: () -> HttpResponse) {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) {
            throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e)
        }
        if (!response.status.isSuccess()) throw response.toException()
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val text = bodyAsText()
        val error = runCatching { AppJson.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(
                when (status.value) { 401 -> ApiError.UNAUTHORIZED; 403 -> ApiError.FORBIDDEN; 404 -> ApiError.NOT_FOUND; else -> ApiError.NETWORK },
                "Server said ${status.value}",
            )
        return ApiException(error)
    }

    private companion object {
        const val SCHOOL_HEADER = "X-School-Id"
    }
}
