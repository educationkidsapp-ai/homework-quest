package quest.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import quest.api.dto.ConfirmSkillsRequest
import quest.api.dto.CreateLessonRequest
import quest.api.dto.GenerateRequest
import quest.api.dto.LessonJob
import quest.api.dto.QuestionSet
import quest.api.dto.UploadFile

/**
 * The only contract the app knows about. `FakeLessonApi` (in-app, seeded) and `KtorLessonApi`
 * (server) both implement it; no screen may depend on which one is behind it.
 */
interface LessonApi {
    /** `POST /lessons` — multipart: files and/or a typed task. Returns the job in `uploading`. */
    suspend fun createLesson(request: CreateLessonRequest, files: List<UploadFile>): LessonJob

    /** `GET /lessons/{id}` */
    suspend fun getLesson(id: String): LessonJob

    /**
     * Emits every status change until the job is terminal (`needs_confirmation`, `ready`, `error`).
     * The default polls [getLesson]; fakes override with scripted delays.
     */
    fun observeLesson(id: String, pollMillis: Long = 1_500): Flow<LessonJob> = flow {
        var last: LessonJob? = null
        while (true) {
            val job = getLesson(id)
            if (job != last) emit(job)
            last = job
            if (job.status.isTerminal) return@flow
            delay(pollMillis)
        }
    }

    /** `POST /lessons/{id}/confirm` — the parent's confirmed skill list; starts generation. */
    suspend fun confirmSkills(id: String, request: ConfirmSkillsRequest): LessonJob

    /** `POST /skills/{id}/generate` — a new set for again / harder / easier. */
    suspend fun generate(skillId: String, request: GenerateRequest): QuestionSet

    /** `DELETE /lessons/{id}/files` — the app calls this automatically once a lesson is ready. */
    suspend fun deleteFiles(id: String)
}

class LessonApiException(val error: quest.api.dto.ApiError, cause: Throwable? = null) :
    Exception("${error.code}: ${error.message}", cause)
