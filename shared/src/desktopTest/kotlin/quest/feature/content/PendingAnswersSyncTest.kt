package quest.feature.content

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.Play
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.core.platform.ManualConnectivity
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.PendingAnswersSync
import quest.feature.content.domain.StopMediaRecord
import quest.feature.content.domain.SubmitOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** M4 (D3): answers kept offline go up by themselves when the network returns — no "Try again" needed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PendingAnswersSyncTest {

    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)

    private class Children(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    /** A queue per lesson and a "server" that answers [answer] for each lesson, or does not answer at all. */
    private class Queue(val pending: MutableMap<String, Int>) : JourneyRepository {
        var reachable = false
        val answer = mutableMapOf<String, SubmitOutcome>()
        val requests = mutableListOf<String>()

        override suspend fun submit(childId: String, lessonId: String): SubmitOutcome {
            requests += lessonId
            if (!reachable) return SubmitOutcome.QUEUED
            val outcome = answer[lessonId] ?: SubmitOutcome.SENT
            if (outcome != SubmitOutcome.CLOSED) pending.remove(lessonId)
            return outcome
        }
        override suspend fun pending(childId: String): Map<String, Int> = pending.toMap()
        override suspend fun pendingCount(childId: String, lessonId: String): Int = pending[lessonId] ?: 0
        override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int) = LevelProgress(lessonId, level, variant, emptyMap(), null)
        override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) {}
        override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = emptyList()
        override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) {}
        override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play) = progress(childId, lesson.id, play.level, play.variant)
        override suspend fun completions(childId: String): List<LessonCompletionInfo> = emptyList()
        override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = emptyMap()
        override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) {}
        override suspend fun flushAttempts(childId: String): Int = 0
        override suspend fun firstTryResults(childId: String, skillId: String, excludeLessons: Set<String>): List<Boolean> = emptyList()
        override suspend fun progressReport(childId: String): ProgressResponse? = null
    }

    private class Harness(val net: ManualConnectivity, val queue: Queue, val sync: PendingAnswersSync, val settled: MutableList<Map<String, SubmitOutcome>>)

    private fun TestScope.harness(online: Boolean, pending: Map<String, Int> = mapOf("exam" to 1)): Harness {
        val net = ManualConnectivity(online)
        val queue = Queue(pending.toMutableMap())
        val sync = PendingAnswersSync(net, queue, Children(maya))
        val settled = mutableListOf<Map<String, SubmitOutcome>>()
        backgroundScope.launch { sync.settled.collect { settled += it } }
        sync.start(backgroundScope)
        runCurrent()
        return Harness(net, queue, sync, settled)
    }

    @Test fun answersGiven_offline_goUpTheMomentTheNetworkReturns_withoutATap() = runTest {
        val h = harness(online = false)
        assertTrue(h.queue.requests.isEmpty(), "nothing is tried while the device has no network")

        h.queue.reachable = true
        h.net.set(true)
        runCurrent()

        assertEquals(listOf("exam"), h.queue.requests)
        assertTrue(h.queue.pending.isEmpty())
        assertEquals(listOf(mapOf("exam" to SubmitOutcome.SENT)), h.settled)
    }

    @Test fun aServerThatIsNotYetReachable_isTriedAgainWithBackoff() = runTest {
        val h = harness(online = false)
        h.net.set(true)                       // the network is back, the server is not (yet)
        runCurrent()
        assertEquals(1, h.queue.requests.size)

        advanceTimeBy(1_999); runCurrent()
        assertEquals(1, h.queue.requests.size, "the first retry waits 2 s")
        advanceTimeBy(1); runCurrent()
        assertEquals(2, h.queue.requests.size)

        advanceTimeBy(4_999); runCurrent()
        assertEquals(2, h.queue.requests.size, "the second waits 5 s")
        h.queue.reachable = true
        advanceTimeBy(1); runCurrent()
        assertEquals(3, h.queue.requests.size)
        assertTrue(h.queue.pending.isEmpty())
        assertEquals(listOf(mapOf("exam" to SubmitOutcome.SENT)), h.settled)

        advanceTimeBy(600_000); runCurrent()
        assertEquals(3, h.queue.requests.size, "nothing left: the retries stop")
    }

    @Test fun goingOfflineAgain_stopsTheRetries_untilTheNextTrigger() = runTest {
        val h = harness(online = true)
        assertEquals(1, h.queue.requests.size)
        h.net.set(false)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, h.queue.requests.size)
    }

    @Test fun aNudge_coming_back_to_the_front_triesAtOnce_evenDuringABackoffWait() = runTest {
        val h = harness(online = true)
        assertEquals(1, h.queue.requests.size)
        h.queue.reachable = true
        h.sync.nudge()
        runCurrent()
        assertEquals(2, h.queue.requests.size, "not 2 s later: now")
        assertTrue(h.queue.pending.isEmpty())
    }

    @Test fun aClosedExam_isReportedAndNotRetried_theOtherLessonsStillGoUp() = runTest {
        val h = harness(online = false, pending = mapOf("exam" to 2, "homework" to 3))
        h.queue.reachable = true
        h.queue.answer["exam"] = SubmitOutcome.CLOSED
        h.net.set(true)
        runCurrent()
        advanceTimeBy(600_000); runCurrent()

        assertEquals(listOf("exam", "homework"), h.queue.requests.sorted())
        assertEquals(mapOf("exam" to 2), h.queue.pending, "a closed exam's answers stay on the device for a re-opening")
        assertEquals(listOf(mapOf("exam" to SubmitOutcome.CLOSED, "homework" to SubmitOutcome.SENT)), h.settled)
    }

    @Test fun nothingQueued_noRequest() = runTest {
        val h = harness(online = true, pending = emptyMap())
        h.sync.nudge(); runCurrent()
        assertTrue(h.queue.requests.isEmpty())
    }
}
