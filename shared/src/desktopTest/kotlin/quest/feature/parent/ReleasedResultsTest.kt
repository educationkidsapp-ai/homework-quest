package quest.feature.parent

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.Play
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.ReleasedResult
import quest.api.dto.Subject
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.StopMediaRecord
import quest.feature.parent.domain.ReleasedResultsUseCase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D16 slice 4 / `docs/teacher-flow.md` step 9: parents see the score and the comment **after release**, and nothing
 * before it. §6 is the other half — the child never sees a number — and the last test here is what keeps it true.
 */
class ReleasedResultsTest {
    private val child = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)

    private class Journey(private val response: ProgressResponse?) : JourneyRepository {
        override suspend fun progressReport(childId: String) = response
        override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int): LevelProgress = error("not used")
        override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) = error("not used")
        override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = emptyList()
        override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) = error("not used")
        override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play): LevelProgress = error("not used")
        override suspend fun completions(childId: String): List<LessonCompletionInfo> = emptyList()
        override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = emptyMap()
        override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) = error("not used")
        override suspend fun flushAttempts(childId: String): Int = 0
        override suspend fun firstTryResults(childId: String, skillId: String): List<Boolean> = emptyList()
    }

    private fun response(vararg results: ReleasedResult) =
        ProgressResponse("c1", emptyList(), emptyList(), 0, null, emptyList(), results.toList())

    private fun result(lessonId: String, releasedAt: Long, score: Int? = 80, comment: String? = null) =
        ReleasedResult(lessonId, "Counting in 2s", LocalDate(2026, 9, 14), Subject.MATH, score, "secure", comment, releasedAt)

    @Test fun nothingIsShownBeforeTheTeacherReleases() = runBlocking {
        val useCase = ReleasedResultsUseCase(Journey(response()))
        assertTrue(useCase(child).isEmpty())
        assertNull(useCase.forLesson(child, "l1"))
    }

    @Test fun anOfflineProgressCallIsNotAnEmptyGradebook() = runBlocking {
        // `progressReport` returns null when the call failed. That is "we do not know", and it must read the same as
        // "nothing released" rather than throwing at the parent.
        val useCase = ReleasedResultsUseCase(Journey(null))
        assertTrue(useCase(child).isEmpty())
    }

    @Test fun releasedResultsComeBackNewestFirst() = runBlocking {
        val useCase = ReleasedResultsUseCase(Journey(response(result("old", 1_000), result("new", 3_000), result("mid", 2_000))))
        assertEquals(listOf("new", "mid", "old"), useCase(child).map { it.lessonId })
    }

    @Test fun theLessonPanelFindsOnlyItsOwnLesson() = runBlocking {
        val useCase = ReleasedResultsUseCase(Journey(response(result("l1", 1_000, 82, "Lovely work."), result("l2", 2_000))))
        val one = useCase.forLesson(child, "l1")
        assertEquals(82, one?.score)
        assertEquals("Lovely work.", one?.comment)
        assertNull(useCase.forLesson(child, "l3"), "a lesson with no released result has none")
    }

    /**
     * §6: no red X, no percentage, no score, no timer where a child can see it. The score arrived on
     * `ProgressResponse.results`, so this is the guard that keeps it inside parent mode — the same shape as
     * `ArchitectureTest`, scanning sources rather than trusting a convention.
     */
    @Test fun noChildModeFeatureReadsAReleasedScore() {
        val childFeatures = listOf("map", "journey", "rewards")
        val offenders = childFeatures.flatMap { feature ->
            File("src/commonMain/kotlin/quest/feature/$feature").walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines()
                        .filter { line -> "ReleasedResult" in line || Regex("""\.results\b""").containsMatchIn(line) }
                        .map { "${file.path}: ${it.trim()}" }
                }
        }
        assertTrue(offenders.isEmpty(), "a child-mode screen reached a released score:\n" + offenders.joinToString("\n"))
    }
}
