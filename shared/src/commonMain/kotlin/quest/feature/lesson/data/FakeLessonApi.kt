package quest.feature.lesson.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import quest.api.LessonApi
import quest.api.LessonApiException
import quest.api.dto.ApiError
import quest.api.dto.ConfirmSkillsRequest
import quest.api.dto.CreateLessonRequest
import quest.api.dto.ExtractedSkill
import quest.api.dto.GenerateMode
import quest.api.dto.GenerateRequest
import quest.api.dto.LessonJob
import quest.api.dto.LessonStatus
import quest.api.dto.Question
import quest.api.dto.QuestionSet
import quest.api.dto.SkillExtraction
import quest.api.dto.Subject
import quest.api.dto.UploadFile
import quest.api.samples.Samples
import quest.api.validation.SchemaValidator
import quest.core.platform.Ids

/**
 * In-app stand-in for the server. Returns the seeded sets from docs/design.md and simulates the
 * `uploading` → `reading` → `needs_confirmation` / `error` → `generating` → `ready` timeline with delays.
 *
 * Demo hooks: a file name or typed task containing "error" produces the `error` state.
 */
class FakeLessonApi(
    private val generator: FakeGenerator = FakeGenerator(),
    private val timings: Timings = Timings(),
) : LessonApi {
    data class Timings(val upload: Long = 1_200, val read: Long = 2_500, val generate: Long = 1_800, val more: Long = 900)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val jobs = mutableMapOf<String, LessonJob>()
    private val skillSpecs = mutableMapOf<String, SkillSpec>()
    private val issued = mutableMapOf<String, MutableSet<String>>()
    private val json = SchemaValidator.json

    override suspend fun createLesson(request: CreateLessonRequest, files: List<UploadFile>): LessonJob {
        val id = Ids.random()
        val job = LessonJob(id, LessonStatus.UPLOADING, request.subject, request.date, sourceFileNames = files.map { it.fileName })
        mutex.withLock { jobs[id] = job }
        val shouldFail = files.any { it.fileName.contains("error", ignoreCase = true) } || request.typedTask?.contains("error", true) == true
        scope.launch {
            delay(timings.upload)
            update(id) { it.copy(status = LessonStatus.READING) }
            delay(timings.read)
            if (shouldFail) {
                update(id) { it.copy(status = LessonStatus.ERROR, error = ApiError(ApiError.UNREADABLE_FILE, "We could not read those slides. Try a clearer photo or a PDF.")) }
            } else {
                val skills = skillsFor(id, request)
                update(id) { it.copy(status = LessonStatus.NEEDS_CONFIRMATION, skills = skills) }
            }
        }
        return job
    }

    private fun skillsFor(lessonId: String, request: CreateLessonRequest): List<ExtractedSkill> {
        val prefix = lessonId.take(4)
        val typed = request.typedTask?.trim()
        if (!typed.isNullOrEmpty()) {
            val name = typed.take(40)
            val skill = ExtractedSkill(
                id = "$prefix-" + slug(name), name = name, subject = request.subject, method = "as written by the parent",
                examples = listOf(typed.take(120)), slideNumbers = listOf(1), confidence = 0.9,
            )
            skillSpecs[skill.id] = SkillSpec(skill.id, skill.name, skill.subject, skill.method, skill.examples)
            return listOf(skill)
        }
        val sample = if (request.subject == Subject.MATH) Samples.skillExtractionMath else Samples.skillExtractionEnglish
        return json.decodeFromString(SkillExtraction.serializer(), sample).skills.map { s ->
            val namespaced = s.copy(id = "$prefix-${s.id}")
            skillSpecs[namespaced.id] = SkillSpec(namespaced.id, s.name, s.subject, s.method, s.examples)
            namespaced
        }
    }

    override suspend fun getLesson(id: String): LessonJob =
        mutex.withLock { jobs[id] } ?: throw LessonApiException(ApiError(ApiError.NOT_FOUND, "No lesson $id"))

    override fun observeLesson(id: String, pollMillis: Long): Flow<LessonJob> = super.observeLesson(id, 250)

    override suspend fun confirmSkills(id: String, request: ConfirmSkillsRequest): LessonJob {
        val job = getLesson(id)
        val confirmed = request.skills.map { c ->
            val existingId = c.id
            if (existingId != null && skillSpecs[existingId] != null) {
                skillSpecs[existingId] = skillSpecs[existingId]!!.copy(name = c.name)
                existingId
            } else {
                val newId = "${id.take(4)}-" + slug(c.name)
                skillSpecs[newId] = SkillSpec(newId, c.name, c.subject, c.method ?: "as written by the parent", listOf(c.name))
                newId
            }
        }
        val updated = update(id) { it.copy(status = LessonStatus.GENERATING, skills = it.skills.map { s -> s.copy(name = request.skills.firstOrNull { c -> c.id == s.id }?.name ?: s.name) }) }
        scope.launch {
            delay(timings.generate)
            val sets = confirmed.map { skillId -> initialSet(skillId, request.practiceLength) }
            update(id) { it.copy(status = LessonStatus.READY, questionSets = sets) }
        }
        return updated
    }

    private fun initialSet(skillId: String, length: Int): QuestionSet {
        val spec = skillSpecs.getValue(skillId)
        val sample = when {
            skillId.endsWith("counting-by-2s") -> Samples.questionSetCountingBy2s
            skillId.endsWith("sh-sound") -> Samples.questionSetShSound
            skillId.endsWith("sight-words-week-3") -> Samples.questionSetSightWords
            else -> null
        }
        val set = if (sample != null && length == 7) {
            val decoded = json.decodeFromString(QuestionSet.serializer(), sample)
            val prefix = skillId.take(4)
            decoded.copy(skillId = skillId, questions = decoded.questions.map { it.withId("$prefix-${it.id}") })
        } else generator.generate(spec, GenerateMode.NORMAL, length, emptySet())
        issued.getOrPut(skillId) { mutableSetOf() } += set.questions.map { it.id }
        return set
    }

    override suspend fun generate(skillId: String, request: GenerateRequest): QuestionSet {
        delay(timings.more)
        val spec = skillSpecs[skillId] ?: SkillSpec(skillId, skillId.replace('-', ' '), Subject.MATH, "number line", emptyList())
        val excluded = request.excludeQuestionIds.toSet() + issued[skillId].orEmpty()
        var set = generator.generate(spec, request.mode, request.length, excluded)
        var attempts = 0
        while (set.questions.size < request.length && attempts++ < 5) set = generator.generate(spec, request.mode, request.length, excluded)
        issued.getOrPut(skillId) { mutableSetOf() } += set.questions.map { it.id }
        return set
    }

    override suspend fun deleteFiles(id: String) {
        delay(150)
        update(id) { it.copy(sourceFileNames = emptyList()) }
    }

    private suspend fun update(id: String, f: (LessonJob) -> LessonJob): LessonJob = mutex.withLock {
        val next = f(jobs.getValue(id))
        jobs[id] = next
        next
    }

    private fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(30).ifEmpty { "skill" }
}

fun Question.withId(newId: String): Question = when (this) {
    is Question.Sequence -> copy(id = newId)
    is Question.Count -> copy(id = newId)
    is Question.Compare -> copy(id = newId)
    is Question.Sound -> copy(id = newId)
    is Question.Word -> copy(id = newId)
    is Question.Trace -> copy(id = newId)
    is Question.ReadTap -> copy(id = newId)
}
