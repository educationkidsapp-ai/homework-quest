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

    /**
     * One answer to a question inside an exam's exit ticket, as an attempt named by [questionId] and queued for upload
     * with the rest — no stop completion of its own (the ticket's comes when it is finished).
     */
    suspend fun recordAnswer(childId: String, lesson: PublishedLesson, play: Play, questionId: String, answer: String, correct: Boolean, stars: Int) {}

    /** The exit-ticket questions of this lesson already answered on this device — each is answered once, ever. */
    suspend fun answeredQuestions(childId: String, lessonId: String): Set<String> = emptySet()
    suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play): LevelProgress
    suspend fun completions(childId: String): List<quest.api.dto.LessonCompletionInfo>
    suspend fun parentUnlocks(childId: String): Map<String, List<Int>>
    suspend fun unlockLevel(childId: String, lessonId: String, level: Int)
    suspend fun flushAttempts(childId: String): Int

    /**
     * §8: sends the queued attempts of **one** lesson now and says how the server took them. An exam is a single
     * sitting inside a window, so its answers go up as they are given and a refusal has to reach the screen.
     *
     * What a refusal does to the queue differs. [SubmitOutcome.CLOSED] **keeps** the answers: the window has shut, but
     * a teacher can re-open the paper for this student, and the answers she gave must then still be there to send.
     * [SubmitOutcome.ALREADY_TAKEN] drops them: the server already holds a handed-in paper, on another device.
     */
    suspend fun submit(childId: String, lessonId: String): SubmitOutcome

    /** How many answers of this lesson are still on the device only. */
    suspend fun pendingCount(childId: String, lessonId: String): Int

    /** Every lesson with answers still on the device only, and how many. */
    suspend fun pending(childId: String): Map<String, Int>

    /**
     * The first-try results of a skill, newest first. Attempts of [excludeLessons] are left out — an exam's answers
     * must not move a skill band the parent can see before the teacher has released the exam.
     */
    suspend fun firstTryResults(childId: String, skillId: String, excludeLessons: Set<String> = emptySet()): List<Boolean>
    suspend fun progressReport(childId: String): ProgressResponse?
}

/**
 * How the server took one lesson's attempts. [QUEUED] is "not reached" and [CLOSED] is "the window has shut": in both
 * the answers stay on the device — to go up later, or after a teacher re-opens the exam.
 */
enum class SubmitOutcome { SENT, QUEUED, ALREADY_TAKEN, CLOSED }

data class StopMediaRecord(val stopId: String, val level: Int, val recordingPath: String?, val drawingPath: String?, val completedAt: Long)
