package quest.feature.content.data

import kotlinx.datetime.LocalDate
import quest.api.ContentApi
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.Play
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary
import quest.api.map.MapAssembler
import quest.api.samples.summary
import quest.core.json.AppJson
import quest.core.db.Db
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.MapRepository
import quest.feature.content.domain.StopMediaRecord
import quest.core.platform.MediaFiles

class LessonRepositoryImpl(private val api: ContentApi, private val db: Db) : LessonRepository {
    private val json = AppJson

    override suspend fun lesson(id: String, version: Int?): PublishedLesson {
        cached(id)?.let { if (version == null || it.version >= version) return it }
        val fresh = api.lesson(id, version)
        db.write { upsertLesson(fresh.id, fresh.version.toLong(), json.encodeToString(PublishedLesson.serializer(), fresh), Today.epochMillis()) }
        return fresh
    }

    override suspend fun cached(id: String): PublishedLesson? = db.read { selectLesson(id).executeAsOneOrNull()?.let { json.decodeFromString(PublishedLesson.serializer(), it.json) } }

    override suspend fun cachedSummaries(): List<PublishedLessonSummary> {
        val ids = db.read { selectLessonVersions().executeAsList().map { it.id } }
        return ids.mapNotNull { cached(it)?.summary() }
    }

    override suspend fun prefetch(map: MapResponse) {
        val local = db.read { selectLessonVersions().executeAsList().associate { it.id to it.version.toInt() } }
        map.islands.filter { it.kind == IslandKind.LESSON }.forEach { island ->
            val id = island.lessonId ?: return@forEach
            if ((local[id] ?: 0) < (island.lessonVersion ?: 1)) runCatching { lesson(id, island.lessonVersion) }
        }
    }
}

class MapRepositoryImpl(private val api: ContentApi, private val lessons: LessonRepository, private val journey: JourneyRepository) : MapRepository {
    override suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate): MapResponse {
        val completions = journey.completions(child.id)
        val unlocks = journey.parentUnlocks(child.id)
        val remote = runCatching { api.map(child.id, from, to) }.getOrNull()
        val base = remote ?: MapAssembler.assemble(child, lessons.cachedSummaries(), completions, emptyList(), unlocks, from, to, today)
        // Local completions win (they may not have been uploaded yet).
        val merged = base.copy(islands = base.islands.map { island ->
            if (island.kind != IslandKind.LESSON) island else {
                val done = completions.filter { it.lessonId == island.lessonId }
                if (done.isEmpty()) island.copy(levelsUnlocked = MapAssembler.unlockedLevels(emptyList(), unlocks[island.lessonId].orEmpty()))
                else island.copy(
                    state = IslandState.DONE, completedLevels = done.map { it.level }.distinct().sorted(),
                    levelsUnlocked = MapAssembler.unlockedLevels(done, unlocks[island.lessonId].orEmpty()),
                    starsEarned = done.maxOf { it.starsEarned }, starsTotal = done.first().starsTotal,
                )
            }
        })
        if (remote != null) runCatching { lessons.prefetch(merged) }
        return merged
    }
}

class JourneyRepositoryImpl(private val api: ContentApi, private val db: Db) : JourneyRepository {
    override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int): LevelProgress = db.read {
        val stops = selectStopCompletions(childId, lessonId, level.toLong(), variant.toLong()).executeAsList().associate { it.stopId to it.stars.toInt() }
        val done = selectLessonCompletionsFor(childId, lessonId).executeAsList().firstOrNull { it.level.toInt() == level && it.variant.toInt() == variant }
        LevelProgress(lessonId, level, variant, stops, done?.completedAt)
    }

    override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) {
        val now = Today.epochMillis()
        val recordingPath = recording?.let { MediaFiles.save("$childId-$stopId-$now.m4a", it) }
        val drawingPath = drawing?.let { MediaFiles.save("$childId-$stopId-$now.json", it.encodeToByteArray()) }
        db.write {
            upsertStopCompletion(childId, lesson.id, play.level.toLong(), play.variant.toLong(), stopId, stars.toLong(), now, recordingPath, drawingPath)
            insertAttempt(Ids.random(), childId, stopId, lesson.id, play.level.toLong(), lesson.skills.joinToString(",") { it.id }, answer, if (correct) 1 else 0, attemptNumber.toLong(), mistakes.toLong(), stars.toLong(), now)
        }
    }

    override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = db.read {
        (1..3).flatMap { level -> selectStopCompletions(childId, lessonId, level.toLong(), 0).executeAsList() }
            .filter { it.recordingPath != null || it.drawingPath != null }
            .map { StopMediaRecord(it.stopId, it.level.toInt(), it.recordingPath, it.drawingPath, it.completedAt) }
    }

    override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) {
        db.write { insertAttempt(Ids.random(), childId, stopId, lesson.id, play.level.toLong(), lesson.skills.joinToString(",") { it.id }, answer, 0, attemptNumber.toLong(), 0, 0, Today.epochMillis()) }
    }

    override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play): LevelProgress {
        val p = progress(childId, lesson.id, play.level, play.variant)
        val stars = p.starsFor(play)
        val twoPlus = play.stops.count { (p.stops[it.id] ?: 0) >= 2 }
        db.write { upsertLessonCompletion(childId, lesson.id, play.level.toLong(), play.variant.toLong(), stars.toLong(), (play.stops.size * 3).toLong(), if (twoPlus * 2 > play.stops.size) 1 else 0, 1, Today.epochMillis()) }
        return p.copy(completedAt = Today.epochMillis())
    }

    override suspend fun completions(childId: String): List<LessonCompletionInfo> = db.read {
        selectLessonCompletions(childId).executeAsList().filter { it.variant == 0L }.map { LessonCompletionInfo(it.lessonId, it.level.toInt(), it.starsEarned.toInt(), it.starsTotal.toInt(), it.mostStopsTwoStars == 1L) }
    }

    override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = db.read { selectParentUnlocks(childId).executeAsList().groupBy({ it.lessonId }, { it.level.toInt() }) }
    override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) = db.write { upsertParentUnlock(childId, lessonId, level.toLong()) }

    override suspend fun flushAttempts(childId: String): Int {
        val pending = db.read { selectPendingAttempts(childId).executeAsList() }
        if (pending.isEmpty()) return 0
        val uploads = pending.map { AttemptUpload(it.id, it.stopId, it.lessonId, it.level.toInt(), it.answerJson, it.correct == 1L, it.attemptNumber.toInt(), it.mistakes.toInt(), it.stars.toInt(), it.answeredAt) }
        val ack = runCatching { api.uploadAttempts(childId, uploads) }.getOrNull() ?: return 0
        db.write { markAttemptsUploaded(pending.map { it.id }) }
        return ack.accepted
    }

    override suspend fun firstTryResults(childId: String, skillId: String): List<Boolean> = db.read { selectFirstTryResultsForSkill(childId, "%$skillId%").executeAsList().map { it == 1L } }

    override suspend fun progressReport(childId: String): ProgressResponse? = runCatching { api.progress(childId) }.getOrNull()
}
