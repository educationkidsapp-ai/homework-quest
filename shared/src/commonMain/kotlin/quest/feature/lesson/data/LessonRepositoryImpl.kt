package quest.feature.lesson.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import quest.api.LessonApi
import quest.api.dto.ApiError
import quest.api.dto.ConfirmSkillsRequest
import quest.api.dto.ConfirmedSkill
import quest.api.dto.CreateLessonRequest
import quest.api.dto.ExtractedSkill
import quest.api.dto.GenerateMode
import quest.api.dto.GenerateRequest
import quest.api.dto.LessonJob
import quest.api.dto.LessonStatus
import quest.api.dto.QuestionSet
import quest.api.dto.Subject
import quest.api.dto.Unsure
import quest.core.db.Db
import quest.core.db.QuestJson
import quest.core.db.QuestionSetDao
import quest.core.db.SettingsStore
import quest.core.platform.Today
import quest.feature.lesson.domain.Lesson
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.NewLesson
import quest.feature.lesson.domain.Skill
import quest.feature.lesson.domain.SkillDecision
import quest.core.db.Lesson as LessonRow
import quest.core.db.Skill as SkillRow

class LessonRepositoryImpl(
    private val api: LessonApi,
    private val db: Db,
    private val sets: QuestionSetDao,
    private val settings: SettingsStore,
    private val childId: suspend () -> String,
) : LessonRepository {

    private val stringList = ListSerializer(String.serializer())

    override suspend fun create(newLesson: NewLesson): LessonJob {
        val child = db.read { selectChild().executeAsOneOrNull() }
        val request = CreateLessonRequest(
            subject = newLesson.subject,
            grade = child?.grade?.toInt() ?: 1,
            curriculum = child?.curriculum ?: "international",
            date = newLesson.date,
            practiceLength = settings.practiceLength(),
            typedTask = newLesson.typedTask,
            fileNames = newLesson.files.map { it.fileName },
        )
        val job = api.createLesson(request, newLesson.files)
        persist(job)
        return job
    }

    override fun observe(lessonId: String): Flow<LessonJob> = api.observeLesson(lessonId).onEach { job ->
        persist(job)
        if (job.status == LessonStatus.READY) runCatching { deleteRemoteFiles(lessonId) }
    }

    override suspend fun confirm(lessonId: String, decisions: List<SkillDecision>): LessonJob {
        val kept = decisions.filter { it.keep }
        val job = api.confirmSkills(
            lessonId,
            ConfirmSkillsRequest(
                skills = kept.map { ConfirmedSkill(id = it.id, name = it.name, subject = it.subject, method = it.method) },
                practiceLength = settings.practiceLength(),
            ),
        )
        // Mark local rows: kept skills confirmed (possibly renamed), the rest unconfirmed.
        db.write {
            decisions.forEach { d -> if (d.id != null) setSkillConfirmed(if (d.keep) 1 else 0, d.name, d.id) }
        }
        persist(job)
        return job
    }

    override suspend fun generate(skillId: String, mode: GenerateMode): QuestionSet {
        val shown = sets.shownQuestionIds(skillId)
        val set = api.generate(skillId, GenerateRequest(mode = mode, excludeQuestionIds = shown, length = settings.practiceLength()))
        return sets.save(set.copy(skillId = skillId, id = null))
    }

    override suspend fun deleteRemoteFiles(lessonId: String) {
        val row = db.read { selectLesson(lessonId).executeAsOneOrNull() } ?: return
        if (row.filesDeleted == 1L) return
        api.deleteFiles(lessonId)
        db.write { markFilesDeleted(lessonId) }
    }

    override suspend fun deleteAllRemoteFiles(): Int {
        val pending = db.read { selectLessonsWithFiles().executeAsList() }
        pending.forEach { runCatching { api.deleteFiles(it.id) } }
        db.write { clearSourceFileNames() }
        return pending.size
    }

    override suspend fun lesson(id: String): Lesson? = db.read { selectLesson(id).executeAsOneOrNull()?.toDomain() }
    override suspend fun lessonsOn(date: LocalDate): List<Lesson> = db.read { selectLessonsByDate(date.toString()).executeAsList().map { it.toDomain() } }
    override suspend fun allLessons(): List<Lesson> = db.read { selectAllLessons().executeAsList().map { it.toDomain() } }
    override suspend fun lessonDates(): List<LocalDate> = db.read { selectLessonDates().executeAsList().map { LocalDate.parse(it) } }
    override suspend fun skill(id: String): Skill? = db.read { selectSkill(id).executeAsOneOrNull()?.toDomain() }
    override suspend fun skillsForLesson(lessonId: String): List<Skill> = db.read { selectSkillsByLesson(lessonId).executeAsList().map { it.toDomain() } }
    override suspend fun confirmedSkillsFor(date: LocalDate): List<Skill> =
        db.read { selectConfirmedSkillsByDate(date.toString(), date.toString()).executeAsList().map { it.toDomain() } }
    override suspend fun allConfirmedSkills(): List<Skill> = db.read {
        selectAllConfirmedSkills().executeAsList().map {
            Skill(
                id = it.id, lessonId = it.lessonId, name = it.name, subject = it.subject.toSubject(), method = it.method,
                confidence = it.confidence, confirmed = it.confirmed == 1L,
                examples = QuestJson.decodeFromString(stringList, it.examplesJson),
                unsure = it.unsureJson?.let { u -> QuestJson.decodeFromString(Unsure.serializer(), u) },
                requeuedFor = it.requeuedFor?.let(LocalDate::parse), lessonDate = LocalDate.parse(it.lessonDate),
            )
        }
    }
    override suspend fun requeue(skillId: String, date: LocalDate?) = db.write { setRequeuedFor(date?.toString(), skillId) }
    override suspend fun latestSet(skillId: String): QuestionSet? = sets.latestForSkill(skillId)
    override suspend fun setById(setId: String): QuestionSet? = sets.load(setId)

    // ---- persistence of a remote job ----
    private suspend fun persist(job: LessonJob) {
        val child = childId()
        val existing = db.read { selectLesson(job.id).executeAsOneOrNull() }
        db.write {
            upsertLesson(
                id = job.id, childId = child, date = job.date.toString(), subject = job.subject.wire(), status = job.status.wire(),
                sourceFileNames = QuestJson.encodeToString(stringList, job.sourceFileNames.ifEmpty { existing?.sourceFileNames?.let { QuestJson.decodeFromString(stringList, it) } ?: emptyList() }),
                createdAt = existing?.createdAt ?: Today.epochMillis(),
                errorCode = job.error?.code, errorMessage = job.error?.message,
                filesDeleted = existing?.filesDeleted ?: 0,
            )
            job.skills.forEach { s ->
                val prior = selectSkill(s.id).executeAsOneOrNull()
                upsertSkill(
                    id = s.id, lessonId = job.id, name = prior?.name ?: s.name, subject = s.subject.wire(), method = s.method,
                    confidence = s.confidence, confirmed = prior?.confirmed ?: 0,
                    examplesJson = QuestJson.encodeToString(stringList, s.examples),
                    unsureJson = s.unsure?.let { QuestJson.encodeToString(Unsure.serializer(), it) },
                    requeuedFor = prior?.requeuedFor,
                )
            }
        }
        if (job.status == LessonStatus.READY) {
            job.questionSets.forEach { set ->
                // A skill the parent added manually arrives here for the first time.
                val known = db.read { selectSkill(set.skillId).executeAsOneOrNull() }
                if (known == null) db.write {
                    upsertSkill(set.skillId, job.id, set.skillId.replace('-', ' '), job.subject.wire(), "added by parent", 1.0, 1, "[]", null, null)
                }
                val already = db.read { selectQuestionSetsBySkill(set.skillId).executeAsList() }
                if (already.isEmpty()) {
                    sets.save(set.copy(id = null))
                }
            }
            db.write { job.questionSets.forEach { setSkillConfirmed(1, selectSkill(it.skillId).executeAsOne().name, it.skillId) } }
        }
    }

    private fun LessonRow.toDomain() = Lesson(
        id = id, date = LocalDate.parse(date), subject = subject.toSubject(), status = status.toStatus(),
        sourceFileNames = QuestJson.decodeFromString(stringList, sourceFileNames), createdAt = createdAt,
        error = errorCode?.let { ApiError(it, errorMessage ?: "") }, filesDeleted = filesDeleted == 1L,
    )

    private fun SkillRow.toDomain() = Skill(
        id = id, lessonId = lessonId, name = name, subject = subject.toSubject(), method = method, confidence = confidence,
        confirmed = confirmed == 1L, examples = QuestJson.decodeFromString(stringList, examplesJson),
        unsure = unsureJson?.let { QuestJson.decodeFromString(Unsure.serializer(), it) },
        requeuedFor = requeuedFor?.let(LocalDate::parse),
    )
}

fun Subject.wire() = name.lowercase()
fun String.toSubject() = Subject.entries.first { it.wire() == this }
fun LessonStatus.wire() = when (this) {
    LessonStatus.NEEDS_CONFIRMATION -> "needs_confirmation"
    else -> name.lowercase()
}
fun String.toStatus() = LessonStatus.entries.first { it.wire() == this }
fun ExtractedSkill.toDomain(lessonId: String) = Skill(id, lessonId, name, subject, method, confidence, false, examples, unsure)
