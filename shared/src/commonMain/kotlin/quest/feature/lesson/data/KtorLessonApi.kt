package quest.feature.lesson.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
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
import quest.api.LessonApi
import quest.api.LessonApiException
import quest.api.dto.ApiError
import quest.api.dto.ConfirmSkillsRequest
import quest.api.dto.CreateLessonRequest
import quest.api.dto.GenerateRequest
import quest.api.dto.LessonJob
import quest.api.dto.QuestionSet
import quest.api.dto.UploadFile
import quest.api.validation.SchemaValidator

/** The real implementation: talks to `server/` over HTTP. Same interface as [FakeLessonApi]. */
class KtorLessonApi(private val baseUrl: String, engineClient: HttpClient) : LessonApi {
    private val client = engineClient.config {
        install(ContentNegotiation) { json(SchemaValidator.json) }
        install(HttpTimeout) { requestTimeoutMillis = 120_000; socketTimeoutMillis = 120_000 }
    }

    override suspend fun createLesson(request: CreateLessonRequest, files: List<UploadFile>): LessonJob = call {
        client.post("$baseUrl/lessons") {
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("request", SchemaValidator.json.encodeToString(CreateLessonRequest.serializer(), request), Headers.build { append(HttpHeaders.ContentType, "application/json") })
                        files.forEach { f ->
                            append("files", f.bytes, Headers.build {
                                append(HttpHeaders.ContentType, f.mimeType)
                                append(HttpHeaders.ContentDisposition, "filename=\"${f.fileName}\"")
                            })
                        }
                    },
                ),
            )
        }
    }

    override suspend fun getLesson(id: String): LessonJob = call { client.get("$baseUrl/lessons/$id") }

    override suspend fun confirmSkills(id: String, request: ConfirmSkillsRequest): LessonJob = call {
        client.post("$baseUrl/lessons/$id/confirm") { contentType(ContentType.Application.Json); setBody(request) }
    }

    override suspend fun generate(skillId: String, request: GenerateRequest): QuestionSet = call {
        client.post("$baseUrl/skills/$skillId/generate") { contentType(ContentType.Application.Json); setBody(request) }
    }

    override suspend fun deleteFiles(id: String) {
        val response = try { client.delete("$baseUrl/lessons/$id/files") } catch (e: Exception) { throw LessonApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e) }
        if (!response.status.isSuccess()) throw response.toException()
    }

    private suspend inline fun <reified T> call(block: () -> HttpResponse): T {
        val response = try { block() } catch (e: LessonApiException) { throw e } catch (e: Exception) {
            throw LessonApiException(ApiError(ApiError.NETWORK, e.message ?: "network"), e)
        }
        if (!response.status.isSuccess()) throw response.toException()
        return response.body()
    }

    private suspend fun HttpResponse.toException(): LessonApiException {
        val text = bodyAsText()
        val error = runCatching { SchemaValidator.json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
            ?: ApiError(if (status.value == 404) ApiError.NOT_FOUND else ApiError.NETWORK, "Server said ${status.value}")
        return LessonApiException(error)
    }
}
