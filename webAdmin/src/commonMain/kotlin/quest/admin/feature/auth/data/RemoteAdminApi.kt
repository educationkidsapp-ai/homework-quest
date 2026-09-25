package quest.admin.feature.auth.data

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
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import quest.api.AdminApi
import quest.api.AdminLesson
import quest.api.AdminSession
import quest.api.ApiException
import quest.api.CacheEntry
import quest.api.CalendarResponse
import quest.api.ConfirmedSkill
import quest.api.CreateLessonRequest
import quest.api.JobRef
import quest.api.LessonFilter
import quest.api.UploadFile
import quest.api.UsageResponse
import quest.api.dto.ApiError
import quest.api.dto.Curriculum
import quest.api.dto.ParentPanel
import quest.api.dto.Play
import quest.api.dto.Stop
import quest.api.validation.SchemaValidator

/** `AdminApi` over HTTP: JWT from sign-in on every request; bodies use the shared codec. */
class RemoteAdminApi(private val baseUrl: String, private val token: () -> String?, engine: HttpClient = HttpClient()) : AdminApi {
    private val client = engine.config {
        install(ContentNegotiation) { json(SchemaValidator.json) }
        install(HttpTimeout) { requestTimeoutMillis = 180_000 }
    }

    private fun HttpRequestBuilder.authed() { token()?.let { header(HttpHeaders.Authorization, "Bearer $it") } }

    override suspend fun signIn(email: String, password: String): AdminSession = call { client.post("$baseUrl/admin/auth/sign-in") { contentType(ContentType.Application.Json); setBody(SignInBody(email, password)) } }
    override suspend fun lessons(filter: LessonFilter): List<AdminLesson> = call {
        client.get("$baseUrl/admin/lessons") {
            authed()
            filter.curriculum?.let { parameter("curriculum", it.name.lowercase()) }; filter.grade?.let { parameter("grade", it) }; filter.subject?.let { parameter("subject", it.name.lowercase()) }
            filter.from?.let { parameter("from", it.toString()) }; filter.to?.let { parameter("to", it.toString()) }
        }
    }
    override suspend fun createLesson(request: CreateLessonRequest): AdminLesson = call { client.post("$baseUrl/admin/lessons") { authed(); contentType(ContentType.Application.Json); setBody(request) } }
    override suspend fun uploadFiles(lessonId: String, files: List<UploadFile>): JobRef = call {
        client.post("$baseUrl/admin/lessons/$lessonId/files") {
            authed()
            setBody(MultiPartFormDataContent(formData {
                files.forEach { f -> append("files", f.bytes, Headers.build { append(HttpHeaders.ContentType, f.mimeType); append(HttpHeaders.ContentDisposition, "filename=\"${f.fileName}\"") }) }
            }))
        }
    }
    override suspend fun lesson(lessonId: String): AdminLesson = call { client.get("$baseUrl/admin/lessons/$lessonId") { authed() } }
    override suspend fun lessonStatus(lessonId: String): quest.api.LessonStatusView = call { client.get("$baseUrl/admin/lessons/$lessonId/status") { authed() } }
    override suspend fun analyze(lessonId: String): JobRef = call { client.post("$baseUrl/admin/lessons/$lessonId/analyze") { authed() } }
    override suspend fun confirmSkills(lessonId: String, skills: List<ConfirmedSkill>): JobRef = call { client.post("$baseUrl/admin/lessons/$lessonId/skills") { authed(); contentType(ContentType.Application.Json); setBody(skills) } }
    override suspend fun updateStop(stopId: String, stop: Stop): Stop = call { client.put("$baseUrl/admin/stops/$stopId") { authed(); contentType(ContentType.Application.Json); setBody(stop) } }
    override suspend fun createPlay(lessonId: String, level: Int, variant: Int): quest.api.AdminPlay = call { client.post("$baseUrl/admin/lessons/$lessonId/plays") { authed(); contentType(ContentType.Application.Json); setBody(quest.api.CreatePlayRequest(level, variant)) } }
    override suspend fun addStop(playId: String, stop: Stop): Stop = call { client.post("$baseUrl/admin/plays/$playId/stops") { authed(); contentType(ContentType.Application.Json); setBody(stop) } }
    override suspend fun deleteStop(stopId: String) { val r = client.delete("$baseUrl/admin/stops/$stopId") { authed() }; if (!r.status.isSuccess()) throw r.toException() }
    override suspend fun reorderStops(playId: String, stopIds: List<String>): Play = call { client.put("$baseUrl/admin/plays/$playId/order") { authed(); contentType(ContentType.Application.Json); setBody(quest.api.ReorderRequest(stopIds)) } }
    override suspend fun uploadImage(lessonId: String, file: UploadFile): quest.api.LessonImage = call {
        client.post("$baseUrl/admin/lessons/$lessonId/images") {
            authed()
            setBody(MultiPartFormDataContent(formData { append("file", file.bytes, Headers.build { append(HttpHeaders.ContentType, file.mimeType); append(HttpHeaders.ContentDisposition, "filename=\"${file.fileName}\"") }) }))
        }
    }
    override suspend fun generateFromText(lessonId: String, text: String): JobRef = call { client.post("$baseUrl/admin/lessons/$lessonId/generate-from-text") { authed(); contentType(ContentType.Application.Json); setBody(quest.api.GenerateFromTextRequest(text)) } }
    /** Bytes of a lesson picture for the phone preview (`/media/pages/{id}` is public; the origin may differ in dev). */
    suspend fun imageBytes(imageId: String): ByteArray? { val r = client.get("$baseUrl/media/pages/$imageId") { authed() }; return if (r.status.isSuccess()) r.readRawBytes() else null }
    override suspend fun regenerateStop(stopId: String): Stop = call { client.post("$baseUrl/admin/stops/$stopId/regenerate") { authed() } }
    override suspend fun regeneratePlay(playId: String): Play = call { client.post("$baseUrl/admin/plays/$playId/regenerate") { authed() } }
    override suspend fun updateParentPanel(lessonId: String, panel: ParentPanel): ParentPanel = call { client.put("$baseUrl/admin/lessons/$lessonId/parent-panel") { authed(); contentType(ContentType.Application.Json); setBody(panel) } }
    override suspend fun publish(lessonId: String): AdminLesson = call { client.post("$baseUrl/admin/lessons/$lessonId/publish") { authed() } }
    override suspend fun unpublish(lessonId: String): AdminLesson = call { client.post("$baseUrl/admin/lessons/$lessonId/unpublish") { authed() } }
    override suspend fun deleteFiles(lessonId: String) { val r = client.delete("$baseUrl/admin/lessons/$lessonId/files") { authed() }; if (!r.status.isSuccess()) throw r.toException() }
    override suspend fun deleteLesson(lessonId: String) { val r = client.delete("$baseUrl/admin/lessons/$lessonId") { authed() }; if (!r.status.isSuccess()) throw r.toException() }
    override suspend fun deleteFailedLessons(): Int { val r = client.delete("$baseUrl/admin/lessons/failed") { authed() }; if (!r.status.isSuccess()) throw r.toException(); return Regex("\\d+").find(r.bodyAsText())?.value?.toInt() ?: 0 }
    override suspend fun retry(lessonId: String): JobRef = call { client.post("$baseUrl/admin/lessons/$lessonId/retry") { authed() } }
    override suspend fun retryStep(lessonId: String, step: quest.api.PipelineStep): JobRef = call { client.post("$baseUrl/admin/lessons/$lessonId/steps/${stepName(step)}/retry") { authed() } }
    private fun stepName(s: quest.api.PipelineStep) = when (s) { quest.api.PipelineStep.UPLOAD -> "upload"; quest.api.PipelineStep.CONVERT -> "convert"; quest.api.PipelineStep.ANALYZE -> "analyze"; quest.api.PipelineStep.SKILLS -> "skills"; quest.api.PipelineStep.GENERATE_L1 -> "generate_L1"; quest.api.PipelineStep.GENERATE_L2 -> "generate_L2"; quest.api.PipelineStep.GENERATE_L3 -> "generate_L3"; quest.api.PipelineStep.GENERATE_AGAIN -> "generate_again"; quest.api.PipelineStep.PANEL -> "panel" }
    override suspend fun cache(): List<CacheEntry> = call { client.get("$baseUrl/admin/cache") { authed() } }
    override suspend fun usage(): UsageResponse = call { client.get("$baseUrl/admin/usage") { authed() } }
    override suspend fun calendar(curriculum: Curriculum, grade: Int, year: Int, month: Int): CalendarResponse = call {
        client.get("$baseUrl/admin/calendar") { authed(); parameter("curriculum", curriculum.name.lowercase()); parameter("grade", grade); parameter("year", year); parameter("month", month) }
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try { block() } catch (e: ApiException) { throw e } catch (e: Exception) { throw ApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e) }
        if (!response.status.isSuccess()) throw response.toException()
        return response.body()
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val text = runCatching { bodyAsText() }.getOrDefault("")
        val error = runCatching { SchemaValidator.json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(when (status.value) { 401 -> ApiError.UNAUTHORIZED; 403 -> ApiError.FORBIDDEN; 404 -> ApiError.NOT_FOUND; else -> ApiError.NETWORK }, "Server said ${status.value}")
        return ApiException(error)
    }

    @kotlinx.serialization.Serializable private data class SignInBody(val email: String, val password: String)
}
