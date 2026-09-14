package quest.feature.content.domain

import kotlinx.datetime.LocalDate
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.MapResponse
import quest.api.dto.Play
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson

/** Published lessons: cached forever per version (§7 offline rule). */
interface LessonRepository {
    suspend fun lesson(id: String, version: Int? = null): PublishedLesson
    suspend fun cached(id: String): PublishedLesson?
    suspend fun cachedSummaries(): List<quest.api.dto.PublishedLessonSummary>
    /** Downloads every lesson on the map that is missing or outdated locally. */
    suspend fun prefetch(map: MapResponse)
}

interface MapRepository {
    /** Server map merged with local completions; falls back to the local cache when offline. */
    suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate): MapResponse
}

data class StopResult(val stopId: String, val stars: Int)
data class LevelProgress(val lessonId: String, val level: Int, val variant: Int, val stops: Map<String, Int>, val completedAt: Long?) {
    fun starsFor(play: Play) = play.stops.sumOf { stops[it.id] ?: 0 }
}

/** The child's own play data: stop and lesson completions, attempts (queued for upload), parent unlocks. */
interface JourneyRepository {
    suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int): LevelProgress
    suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray? = null, drawing: String? = null)
    /** Saved recordings / drawings for the parent panel. */
    suspend fun media(childId: String, lessonId: String): List<StopMediaRecord>
    suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int)
    suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play): LevelProgress
    suspend fun completions(childId: String): List<quest.api.dto.LessonCompletionInfo>
    suspend fun parentUnlocks(childId: String): Map<String, List<Int>>
    suspend fun unlockLevel(childId: String, lessonId: String, level: Int)
    suspend fun flushAttempts(childId: String): Int
    suspend fun firstTryResults(childId: String, skillId: String): List<Boolean>
    suspend fun progressReport(childId: String): ProgressResponse?
}

data class StopMediaRecord(val stopId: String, val level: Int, val recordingPath: String?, val drawingPath: String?, val completedAt: Long)
