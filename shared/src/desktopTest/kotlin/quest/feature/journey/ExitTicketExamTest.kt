package quest.feature.journey

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import quest.api.ContentApi
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.MapResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.Stop
import quest.api.samples.MathSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.domain.LessonRepository
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.school.domain.FlagStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3 found it (M4): the server scores an exit ticket by the questions inside it (`AnswerKey.index`, `Scoring` flattens
 * the ticket), so in an exam each of those questions must go up as its own attempt, by its own id, with its answer —
 * not one attempt on the wrapper that the server cannot grade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExitTicketExamTest {
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val exam: PublishedLesson = MathSeed.lesson.let { it.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[0]) }
    private val paper = exam.examPlay!!
    private val ticket = paper.stops.filterIsInstance<Stop.ExitTicket>().single()
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val uploads = mutableListOf<AttemptUpload>()
    private val fake = FakeContentApi(FakeAuth(settings), delayMillis = 0)
    private val api = object : ContentApi by fake {
        override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck { uploads += attempts; return AttemptAck(attempts.size) }
    }
    private var vm: StopPlayerViewModel? = null

    /** The repository reads and writes the database off the test's dispatcher: wait on the wall clock, briefly. */
    private fun kotlinx.coroutines.test.TestScope.await(what: String, cond: () -> Boolean) {
        repeat(500) { runCurrent(); if (cond()) return; Thread.sleep(10) }
        error("never: $what")
    }

    @AfterTest fun tearDown() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    @Test fun eachQuestionOfAnExitTicketGoesUpAsItsOwnAttempt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val journey = JourneyRepositoryImpl(api, db)
        // Everything before the ticket answered and delivered: the sitting stands on the ticket.
        paper.stops.takeWhile { it !is Stop.ExitTicket }.forEach { journey.recordStop(maya.id, exam, paper, it.id, 3, "a", true, 1, 0) }
        journey.submit(maya.id, exam.id); uploads.clear()

        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh() = listOf(maya)
            override suspend fun children() = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val lessons = object : LessonRepository {
            override suspend fun lesson(id: String, version: Int?) = exam
            override suspend fun cached(id: String) = exam
            override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
            override suspend fun prefetch(map: MapResponse) {}
        }
        val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
        val player = StopPlayerViewModel(exam.id, 1, 0, 0, lessons, journey, children, api, copy).also { vm = it }
        await("the sitting opens") { player.state.value.phase == PlayerContract.Phase.STOP }
        assertEquals(ticket.id, player.state.value.stop?.id)

        // What ExitTicketStop reports in exam mode: one answer per question, then the ticket finished.
        val (q1, q2, q3) = ticket.questions
        player.dispatch(PlayerContract.Intent.QuestionAnswered(q1.id, "10", correct = true, stars = 3))
        player.dispatch(PlayerContract.Intent.QuestionAnswered(q2.id, "n2,n5", correct = false, stars = 0))
        player.dispatch(PlayerContract.Intent.QuestionAnswered(q3.id, "14", correct = true, stars = 3))
        player.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "", mistakes = 1, correct = false))
        await("the answers go up") { uploads.size >= 4 }

        val byStop = uploads.associateBy { it.stopId }
        listOf(q1, q2, q3).forEach { q -> assertTrue(q.id in byStop, "question ${q.id} goes up on its own") }
        assertEquals("n2,n5", byStop.getValue(q2.id).answerJson)
        assertEquals(false, byStop.getValue(q2.id).correct)
        assertTrue(uploads.all { it.lessonId == exam.id })
    }

    @Test fun outsideAnExam_orAfterTheTicketIsFinished_noQuestionAttemptIsRecorded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val journey = JourneyRepositoryImpl(api, db)
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh() = listOf(maya)
            override suspend fun children() = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val homework = MathSeed.lesson
        val lessons = object : LessonRepository {
            override suspend fun lesson(id: String, version: Int?) = homework
            override suspend fun cached(id: String) = homework
            override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
            override suspend fun prefetch(map: MapResponse) {}
        }
        val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
        val index = homework.plays[0].stops.indexOfFirst { it is Stop.ExitTicket }
        val player = StopPlayerViewModel(homework.id, 1, 0, index, lessons, journey, children, api, copy).also { vm = it }
        await("the lesson opens") { player.state.value.phase == PlayerContract.Phase.STOP }
        player.dispatch(PlayerContract.Intent.QuestionAnswered(ticket.questions[0].id, "10", true, 3)); runCurrent(); Thread.sleep(100); runCurrent()
        assertTrue(journey.pending(maya.id).isEmpty(), "a homework keeps its own exit-ticket flow")
    }

    /** Review of #197: after "Continue exam" or an app kill the ticket resumes at its next question, and no question is sent twice. */
    @Test fun aKilledSittingResumesTheTicketAtItsNextQuestion_andNeverSendsAQuestionTwice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val journey = JourneyRepositoryImpl(api, db)
        paper.stops.takeWhile { it !is Stop.ExitTicket }.forEach { journey.recordStop(maya.id, exam, paper, it.id, 3, "a", true, 1, 0) }
        journey.submit(maya.id, exam.id); uploads.clear()
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh() = listOf(maya)
            override suspend fun children() = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val lessons = object : LessonRepository {
            override suspend fun lesson(id: String, version: Int?) = exam
            override suspend fun cached(id: String) = exam
            override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
            override suspend fun prefetch(map: MapResponse) {}
        }
        val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
        val (q1, q2, _) = ticket.questions

        val first = StopPlayerViewModel(exam.id, 1, 0, 0, lessons, journey, children, api, copy).also { vm = it }
        await("the sitting opens") { first.state.value.phase == PlayerContract.Phase.STOP }
        first.dispatch(PlayerContract.Intent.QuestionAnswered(q1.id, "10", correct = true, stars = 3))
        await("q1 recorded") { kotlinx.coroutines.runBlocking { journey.answeredQuestions(maya.id, exam.id) }.contains(q1.id) }
        first.viewModelScope.cancel()                                     // the app is killed

        val second = StopPlayerViewModel(exam.id, 1, 0, 0, lessons, journey, children, api, copy).also { vm = it }
        await("the sitting reopens") { second.state.value.phase == PlayerContract.Phase.STOP }
        assertEquals(ticket.id, second.state.value.stop?.id)
        assertTrue(q1.id in second.state.value.answeredQuestions, "the ticket knows q1 is answered, so it opens at q2")
        second.dispatch(PlayerContract.Intent.QuestionAnswered(q1.id, "12", correct = false, stars = 0))   // a stale re-answer
        second.dispatch(PlayerContract.Intent.QuestionAnswered(q2.id, "n2,n8", correct = true, stars = 3))
        await("q2 recorded") { kotlinx.coroutines.runBlocking { journey.answeredQuestions(maya.id, exam.id) }.contains(q2.id) }
        journey.recordAnswer(maya.id, exam, paper, q1.id, "x", false, 0)                                  // and at the repository too
        journey.submit(maya.id, exam.id)

        val q1Attempts = kotlinx.coroutines.runBlocking { db.read { selectAllAttempts(maya.id).executeAsList() } }.filter { it.stopId == q1.id }
        assertEquals(1, q1Attempts.size, "one attempt per question, ever")
        assertEquals("10", q1Attempts.single().answerJson)
    }
}
