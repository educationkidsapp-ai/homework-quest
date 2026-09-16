package quest.feature.content.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.UploadFile
import quest.api.dashboard.JoinSchoolInfo
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.MapResponse
import quest.api.dto.MediaKind
import quest.api.dto.MediaRef
import quest.api.dto.PlatformSettings
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.SchoolTheme
import quest.api.dto.UpdateChildRequest
import quest.api.validation.SchemaValidator
import quest.feature.content.domain.SchoolApi
import quest.feature.content.domain.ThemeFetch

/** The real implementation: Spring Boot API over HTTP with the Firebase ID token on every request. */
class RemoteContentApi(private val baseUrl: String, private val auth: AuthProvider, engineClient: HttpClient) : ContentApi, SchoolApi {
    private val client = engineClient.config {
        install(ContentNegotiation) { json(SchemaValidator.json) }
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

    // ---- §2 join school, §3 theme, §4 flags, §A platform settings. All four routes are public: no bearer token,
    // because the parent types a school code before the child (and sometimes before the account) exists.
    override suspend fun schoolByCode(code: String): JoinSchoolInfo =
        call { client.get("$baseUrl/schools/by-code/${code.trim().uppercase()}") }

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

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) { throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e) }
        if (!response.status.isSuccess()) throw response.toException()
        return response.body()
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val text = bodyAsText()
        val error = runCatching { SchemaValidator.json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(when (status.value) { 401 -> ApiError.UNAUTHORIZED; 403 -> ApiError.FORBIDDEN; 404 -> ApiError.NOT_FOUND; else -> ApiError.NETWORK }, "Server said ${status.value}")
        return ApiException(error)
    }
}
