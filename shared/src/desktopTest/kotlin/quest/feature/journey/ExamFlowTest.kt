package quest.feature.journey

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.IslandState
import quest.api.dto.ProgressResponse
import quest.api.dto.ReleasedResult
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.FakeExam
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.data.LessonRepositoryImpl
import quest.feature.content.data.MapRepositoryImpl
import quest.feature.content.domain.SubmitOutcome
import quest.feature.map.presentation.ExamStatus
import quest.feature.map.presentation.examStatus
import quest.feature.parent.domain.ProgressReportUseCase
import quest.feature.parent.domain.UndeliveredExamAnswersUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §8 end to end: the app's own repositories and an in-memory database against the fake API's exam, which applies the
 * server's rules — a window read on the *server's* clock, one sitting, 409s that refuse a whole batch, and a
 * teacher's re-opening. The device clock never appears here: the only clock is the server's, and the test moves it.
 */
class ExamFlowTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth: AuthProvider = FakeAuth(settings)
    private var serverNow = 1_790_000_000_000L
    private var offline = false
    private var released = emptyList<String>()
    private val fake = FakeContentApi(auth, delayMillis = 0, today = { LocalDate(2026, 10, 2) }, now = { serverNow })
    private val api = object : ContentApi by fake {
        override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck {
            if (offline) throw ApiException(ApiError(ApiError.NETWORK, "offline"))
            return fake.uploadAttempts(childId, attempts)
        }
        override suspend fun progress(childId: String): ProgressResponse {
            if (offline) throw ApiException(ApiError(ApiError.NETWORK, "offline"))
            return fake.progress(childId).copy(results = released.map { ReleasedResult(it, "Counting exam", LocalDate(2026, 10, 2), FakeExam.lesson.subject, 50, "developing", null, serverNow) })
        }
    }
    private val children = ChildrenRepositoryImpl(api, db, settings, auth)
    private val lessons = LessonRepositoryImpl(api, db)
    private val journey = JourneyRepositoryImpl(api, db)
    private val maps = MapRepositoryImpl(api, lessons, journey)
    private val paper = FakeExam.lesson.examPlay!!
    private val day = LocalDate(2026, 10, 2)

    private suspend fun signIn(): Child {
        auth.signIn("parent@example.com", "secret123")
        return children.refresh().first().also { children.select(it.id) }
    }

    private suspend fun examIsland(child: Child) = maps.map(child, LocalDate(2026, 9, 1), LocalDate(2026, 10, 31), day).islands.firstOrNull { it.lessonId == FakeExam.LESSON_ID }

    /** One answer, as the player records it: stored locally (the question is then answered), then handed to the server. */
    private suspend fun answer(child: Child, index: Int, correct: Boolean = false): SubmitOutcome {
        val exam = lessons.lesson(FakeExam.LESSON_ID)
        journey.recordStop(child.id, exam, paper, paper.stops[index].id, if (correct) 3 else 0, "answer-$index", correct, 1, if (correct) 0 else 1)
        return journey.submit(child.id, FakeExam.LESSON_ID)
    }

    @Test fun aWholeSittingIsHandedInAndCannotBeSatAgain() = runTest {
        val child = signIn()
        val island = examIsland(child)!!
        assertEquals(ExamStatus.OPEN, examStatus(island, loadedAt = serverNow))
        assertTrue(lessons.lesson(FakeExam.LESSON_ID).examPlay != null)

        paper.stops.indices.forEach { assertEquals(SubmitOutcome.SENT, answer(child, it, correct = it % 2 == 0)) }
        assertTrue(fake.examIsSubmitted(child.id))
        assertEquals(paper.stops.map { it.id }.toSet(), fake.examAnswers(child.id).keys)
        // Wrong answers are on the paper as given — the server marks it, the app never said which they were.
        assertFalse(fake.examAnswers(child.id).getValue(paper.stops[1].id).correct)

        journey.completeLevel(child.id, lessons.lesson(FakeExam.LESSON_ID), paper)
        assertEquals(IslandState.DONE, examIsland(child)!!.state)
        assertEquals(ExamStatus.SUBMITTED, examStatus(examIsland(child)!!, loadedAt = serverNow))

        // A second sitting — another device, or a replay — is the server's 409, and those answers are dropped.
        journey.recordStop(child.id, lessons.lesson(FakeExam.LESSON_ID), paper, paper.stops[0].id, 3, "again", true, 1, 0)
        assertEquals(SubmitOutcome.ALREADY_TAKEN, journey.submit(child.id, FakeExam.LESSON_ID))
        assertEquals(0, journey.pendingCount(child.id, FakeExam.LESSON_ID))
        assertEquals("answer-0", fake.examAnswers(child.id).getValue(paper.stops[0].id).answerJson)
    }

    @Test fun theWindowShuttingMidSittingKeepsTheAnswerAndAReopeningDeliversIt() = runTest {
        val child = signIn()
        assertEquals(SubmitOutcome.SENT, answer(child, 0))
        assertEquals(SubmitOutcome.SENT, answer(child, 1))

        serverNow = fake.examClosesAt + 1                          // the server's clock passes the end of the window
        assertEquals(SubmitOutcome.CLOSED, answer(child, 2))
        assertEquals(1, journey.pendingCount(child.id, FakeExam.LESSON_ID))
        assertNull(examIsland(child), "a closed exam is not on the map")
        assertEquals(2, fake.examAnswers(child.id).size)
        // The parent is told, by title and count.
        val notice = UndeliveredExamAnswersUseCase(journey, lessons)(child).single()
        assertEquals(FakeExam.lesson.title, notice.title); assertEquals(1, notice.answers)

        fake.reopenExam(child.id)                                  // the teacher gives her one more sitting
        val island = examIsland(child)!!
        assertEquals(ExamStatus.REOPENED, examStatus(island, loadedAt = serverNow))
        // Opening the exam again sends what was left on the device first (the player does this on load)…
        assertEquals(SubmitOutcome.SENT, journey.submit(child.id, FakeExam.LESSON_ID))
        assertEquals(3, fake.examAnswers(child.id).size)
        assertTrue(UndeliveredExamAnswersUseCase(journey, lessons)(child).isEmpty())
        // …the sitting resumes at the first question she has not answered, and the paper can be completed.
        val progress = journey.progress(child.id, FakeExam.LESSON_ID, paper.level, paper.variant)
        assertEquals(3, paper.stops.indexOfFirst { it.id !in progress.stops })
        (3..paper.stops.lastIndex).forEach { assertEquals(SubmitOutcome.SENT, answer(child, it)) }
        assertTrue(fake.examIsSubmitted(child.id))
    }

    @Test fun answersGivenOfflineAreNotLostWhenTheWindowShutsBeforeTheyAreSent() = runTest {
        val child = signIn()
        assertEquals(SubmitOutcome.SENT, answer(child, 0))
        offline = true
        (1..paper.stops.lastIndex).forEach { assertEquals(SubmitOutcome.QUEUED, answer(child, it)) }
        val unsent = paper.stops.size - 1
        assertEquals(unsent, journey.pendingCount(child.id, FakeExam.LESSON_ID))
        assertFalse(fake.examIsSubmitted(child.id), "every question is answered on the device, and the server has one answer: not submitted")

        offline = false; serverNow = fake.examClosesAt + 1         // online again, too late
        assertEquals(SubmitOutcome.CLOSED, journey.submit(child.id, FakeExam.LESSON_ID))
        assertEquals(unsent, journey.pendingCount(child.id, FakeExam.LESSON_ID), "refused, and still here")
        assertEquals(unsent, UndeliveredExamAnswersUseCase(journey, lessons)(child).single().answers)

        fake.reopenExam(child.id)
        assertEquals(SubmitOutcome.SENT, journey.submit(child.id, FakeExam.LESSON_ID))
        assertTrue(fake.examIsSubmitted(child.id))
        assertEquals(paper.stops.size, fake.examAnswers(child.id).size)
    }

    /** Review point 5: an unreleased exam moves nothing the parent can see on Progress. */
    @Test fun anUnreleasedExamDoesNotMoveASkillBandOnTheParentsProgress() = runTest {
        val child = signIn()
        lessons.lesson(FakeExam.LESSON_ID)                          // cached, as after a sitting
        val report = ProgressReportUseCase(journey, lessons)
        val skill = FakeExam.lesson.skills.first().id
        offline = true                                              // answers stay local, so only the local record could show them
        paper.stops.indices.forEach { answer(child, it, correct = false) }

        assertEquals(0, report(child).first { it.skillId == skill }.attempts, "offline: nothing is known to be released")
        offline = false
        val unreleased = report(child).first { it.skillId == skill }
        assertEquals(0, unreleased.attempts); assertNull(unreleased.band)

        released = listOf(FakeExam.LESSON_ID)                       // the teacher releases the exam
        assertTrue(report(child).first { it.skillId == skill }.attempts > 0)
    }
}
