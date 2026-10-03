package quest.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import quest.feature.today.domain.TodayJson
import quest.feature.chat.domain.ChatConnectionState
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatTopic
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.ExamWindow
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.Play
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.broadcasts.domain.NoAttachmentDocuments
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.domain.SignOutUseCase
import quest.feature.content.data.FakeContentApi
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.StopMediaRecord
import quest.feature.content.domain.SubmitOutcome
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.school.domain.FlagStore
import quest.feature.today.domain.ExamSitting
import quest.feature.today.domain.ExamSittingPresenter
import quest.feature.today.domain.ExamWindows
import quest.feature.today.domain.PublishTodayUseCase
import quest.feature.today.domain.TodayLabels
import quest.feature.today.domain.TodayLink
import quest.feature.today.domain.TodayLinks
import quest.feature.today.domain.TodaySnapshot
import quest.feature.today.domain.TodaySnapshotStore
import quest.feature.today.domain.TodaySnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M3 — what the home-screen widget is told and what the exam's system activity is shown, tested on the shared code
 * that decides both. The widget and the Live Activity themselves only draw these values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayTest {
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val now = 1_790_000_000_000L
    private val day = LocalDate(2026, 10, 2)
    private fun lesson(id: String, title: String, state: IslandState = IslandState.TODAY, kind: IslandKind = IslandKind.LESSON, window: ExamWindow? = null) =
        Island("i-$id", kind, day, state, title, Subject.MATH, id, examWindow = window)

    private class RecordingStore : TodaySnapshotStore {
        val written = mutableListOf<TodaySnapshot?>()
        override suspend fun write(snapshot: TodaySnapshot?) { written += snapshot }
    }

    private class Chat(private val unread: List<Int>, private val fails: Boolean = false) : ChatRepository {
        override val connectionState = MutableStateFlow(ChatConnectionState.CONNECTED) as StateFlow<ChatConnectionState>
        override val incomingFrames = MutableSharedFlow<ChatFrame>()
        override suspend fun threads(childId: String): List<ChatThread> = if (fails) error("offline") else unread.mapIndexed { i, n -> ChatThread(childId = childId, childName = "Maya", teacherId = "t$i", teacherName = "Teacher $i", unread = n) }
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?) = emptyList<ChatMessage>()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic?) = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) {}
        override suspend fun sendTyping(childId: String, teacherId: String) {}
        override fun connect() {}
        override fun disconnect() {}
    }

    // ---------------------------------------------------------------- the snapshot

    @Test fun theSnapshotCountsLessonsToDoAndNamesTheFirstTwo() {
        val islands = listOf(
            lesson("a", "Counting by 2s"), lesson("b", "The sh sound", IslandState.WAITING), lesson("c", "Hot Soup"),
            lesson("d", "Done already", IslandState.DONE), lesson("e", "Still asleep", IslandState.LOCKED, IslandKind.LOCKED),
        )
        val s = TodaySnapshots.of("Maya", islands, exams = emptySet(), unreadMessages = 3, now = now, labels = TodayLabels(), rtl = false)
        assertEquals("Maya", s.childName)
        assertEquals(3, s.lessonsToDo)
        assertEquals(listOf("Counting by 2s", "The sh sound"), s.lessonTitles)
        assertNull(s.nextExamTitle)
        assertEquals(3, s.unreadMessages)
        assertEquals(now, s.updatedAt)
    }

    @Test fun anExamIsTheNextExamAndNeverCountedAsALesson() {
        val later = lesson("x2", "Science exam", window = ExamWindow(now - 1, now + 7_200_000))
        val sooner = lesson("x1", "Reading exam", window = ExamWindow(now - 1, now + 3_600_000))
        val handedIn = lesson("x3", "Old exam", IslandState.DONE, window = ExamWindow(now - 1, now + 60_000))
        val s = TodaySnapshots.of("Maya", listOf(lesson("a", "Counting by 2s"), later, sooner, handedIn), setOf("x1", "x2", "x3"), 0, now, TodayLabels(), false)
        assertEquals(1, s.lessonsToDo)
        assertEquals("Reading exam", s.nextExamTitle, "the one closing soonest")
        assertEquals(now + 3_600_000, s.nextExamClosesAt)
    }

    @Test fun aReopenedExamIsShownWithoutAClosingTime() {
        val reopened = lesson("x1", "Reading exam", window = ExamWindow(now - 7_200_000, now - 60_000))
        val s = TodaySnapshots.of("Maya", listOf(reopened), setOf("x1"), 0, now, TodayLabels(), false)
        assertEquals("Reading exam", s.nextExamTitle)
        assertNull(s.nextExamClosesAt)
    }

    /** The record is all the widget gets: it must carry no id, token or address — only what the home screen may show. */
    @Test fun theSnapshotCarriesNothingButWhatTheWidgetDraws() {
        val json = TodayJson.encodeToString(TodaySnapshots.of("Maya", listOf(lesson("lesson-secret-id", "Counting by 2s")), emptySet(), 2, now, LessonStrings.ar.today, rtl = true))
        assertFalse(json.contains("lesson-secret-id")); assertFalse(json.contains(maya.id + "\""))
        listOf("token", "email", "uid", "childId", "schoolId").forEach { assertFalse(json.contains(it, ignoreCase = true), it) }
        assertTrue(json.contains("\"rtl\":true"))
        assertTrue(json.contains(LessonStrings.ar.today.exam), "the words travel with it, in the parent's language")
        // Every field is written, defaults too: the Swift widget cannot fill in what is left out.
        val english = TodayJson.encodeToString(TodaySnapshots.of("Maya", emptyList(), emptySet(), 0, now, TodayLabels(), rtl = false))
        listOf("labels", "allDone", "rtl", "unreadMessages", "lessonTitles").forEach { assertTrue(english.contains("\"$it\""), it) }
    }

    @Test fun publishingWritesTheSnapshotWithTheUnreadCountAndRemembersExamWindows() = runTest {
        val store = RecordingStore(); val windows = ExamWindows()
        val exam = lesson("x1", "Reading exam", window = ExamWindow(now - 1, now + 3_600_000))
        PublishTodayUseCase(store, Chat(listOf(2, 0, 1)), windows)(maya, listOf(lesson("a", "Counting by 2s"), exam), setOf("x1"), now, TodayLabels(), rtl = false)
        val s = store.written.single()!!
        assertEquals(3, s.unreadMessages); assertEquals(1, s.lessonsToDo); assertEquals("Reading exam", s.nextExamTitle)
        assertEquals(now + 3_600_000, windows.closesAt("x1", now))
        assertNull(windows.closesAt("x1", now + 3_600_000), "an end that has passed is not an end to count down to")
        assertNull(windows.closesAt("unknown", now))
    }

    @Test fun aSchoolWithoutMessagingStillGetsItsWidget() = runTest {
        val store = RecordingStore()
        PublishTodayUseCase(store, Chat(emptyList(), fails = true), ExamWindows())(maya, listOf(lesson("a", "Counting by 2s")), emptySet(), now, TodayLabels(), false)
        assertEquals(0, store.written.single()!!.unreadMessages)
    }

    // ---------------------------------------------------------------- sign-out

    @Test fun signingOutEmptiesTheWidgetAndEndsTheExamActivity() = runTest {
        val store = RecordingStore(); val presenter = RecordingPresenter()
        val settings = SettingsStore(Db(DriverFactory(null)))
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh(): List<Child> = listOf(maya)
            override suspend fun children(): List<Child> = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val documents = NoAttachmentDocuments
        // Wired exactly as `contentModule` wires it.
        SignOutUseCase(FakeAuth(settings), children, documents, alsoForget = { store.write(null); presenter.end() })()
        assertEquals(listOf<TodaySnapshot?>(null), store.written)
        assertEquals(1, presenter.ended)
    }

    // ---------------------------------------------------------------- widget taps

    @Test fun aWidgetTapIsKeptUntilTheAppFollowsItAndUnknownOnesAreIgnored() {
        TodayLinks.consumed()
        TodayLinks.open("messages")
        assertEquals(TodayLink.MESSAGES, TodayLinks.pending.value)
        TodayLinks.consumed()
        assertNull(TodayLinks.pending.value)
        TodayLinks.open("https://example.com/anything"); TodayLinks.open(null)
        assertNull(TodayLinks.pending.value)
        assertEquals(TodayLink.HOME, TodayLink.of("home"))
    }

    // ---------------------------------------------------------------- the exam sitting outside the app

    private class RecordingPresenter : ExamSittingPresenter {
        val shown = mutableListOf<ExamSitting>()
        var ended = 0
        override fun show(sitting: ExamSitting) { shown += sitting }
        override fun end() { ended++ }
    }

    private val exam: PublishedLesson = HotSoupSeed.lesson.let { it.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[1]) }
    private val paper: Play = exam.examPlay!!

    private class Lessons(private val lesson: PublishedLesson) : LessonRepository {
        override suspend fun lesson(id: String, version: Int?): PublishedLesson = lesson
        override suspend fun cached(id: String): PublishedLesson? = lesson
        override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
        override suspend fun prefetch(map: MapResponse) {}
    }

    private class Journey(answered: List<String>, var outcome: SubmitOutcome = SubmitOutcome.SENT) : JourneyRepository {
        val stars = answered.associateWith { 0 }.toMutableMap()
        override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int) = LevelProgress(lessonId, level, variant, stars.toMap(), null)
        override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) { this.stars[stopId] = stars }
        override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = emptyList()
        override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) {}
        override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play) = progress(childId, lesson.id, play.level, play.variant)
        override suspend fun completions(childId: String): List<LessonCompletionInfo> = emptyList()
        override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = emptyMap()
        override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) {}
        override suspend fun flushAttempts(childId: String): Int = 0
        override suspend fun submit(childId: String, lessonId: String): SubmitOutcome = outcome
        override suspend fun pendingCount(childId: String, lessonId: String): Int = 0
        override suspend fun pending(childId: String): Map<String, Int> = emptyMap()
        override suspend fun firstTryResults(childId: String, skillId: String, excludeLessons: Set<String>): List<Boolean> = emptyList()
        override suspend fun progressReport(childId: String): ProgressResponse? = null
    }

    private val settings = SettingsStore(Db(DriverFactory(null)))
    private val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
    private val built = mutableListOf<ViewModel>()

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        runCatching { Dispatchers.resetMain() }
    }

    private fun TestScope.sit(journey: Journey, presenter: RecordingPresenter, windows: ExamWindows): StopPlayerViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh(): List<Child> = listOf(maya)
            override suspend fun children(): List<Child> = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val vm = StopPlayerViewModel(exam.id, 1, 0, 0, Lessons(exam), journey, children, FakeContentApi(FakeAuth(settings), delayMillis = 0), copy, sitting = presenter, windows = windows, now = { now })
        built.add(vm); runCurrent()
        return vm
    }

    @Test fun aSittingIsShownWhenItOpensAndUpdatedAfterEachAnswerWithTheCountOnly() = runTest {
        val presenter = RecordingPresenter()
        val windows = ExamWindows().apply { remember(mapOf(exam.id to now + 1_800_000)) }
        val vm = sit(Journey(paper.stops.take(2).map { it.id }), presenter, windows)

        assertEquals(ExamSitting(maya.id, exam.id, exam.title, "Maya", now + 1_800_000, answered = 2, total = paper.stops.size, windowEnded = "Exam window ended"), presenter.shown.single())

        vm.dispatch(PlayerContract.Intent.Completed(stars = 0, answer = "x", mistakes = 1, correct = false))   // a wrong answer
        runCurrent()
        assertEquals(3, presenter.shown.last().answered, "the count moves; nothing in it says the answer was wrong")
        assertEquals(0, presenter.ended)
    }

    @Test fun aSittingIsKnownByItsStudentAndPaperSoALeftoverOfAnotherIsNeverReused() {
        val a = ExamSitting("c1", "exam-a", "Fractions", "Maya", null, answered = 1, total = 5)
        assertEquals(a.key, a.copy(title = "Renamed", answered = 4, closesAt = now).key, "the count, title and window do not change which sitting it is")
        assertNotEquals(a.key, a.copy(lessonId = "exam-b").key, "another paper")
        assertNotEquals(a.key, a.copy(childId = "c2").key, "a sibling's sitting of the same paper")
    }

    @Test fun aSittingIsTakenDownAtItsWindowsEndOrAfterTheCapWhenTheEndIsUnknown() {
        val known = ExamSitting("c1", "exam-a", "Fractions", "Maya", now + 600_000, answered = 0, total = 5)
        assertEquals(now + 600_000, known.takeDownAt(now))
        assertEquals(now + ExamSitting.LONGEST_MILLIS, known.copy(closesAt = null).takeDownAt(now))
    }

    @Test fun handingInEndsIt() = runTest {
        val presenter = RecordingPresenter()
        val vm = sit(Journey(paper.stops.dropLast(1).map { it.id }), presenter, ExamWindows())
        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceUntilIdle(); runCurrent()
        assertEquals(PlayerContract.Phase.DONE, vm.state.value.phase)
        assertEquals(1, presenter.ended)
    }

    @Test fun aRefusalOrAClosedWindowEndsIt() = runTest {
        listOf(SubmitOutcome.ALREADY_TAKEN, SubmitOutcome.CLOSED).forEach { outcome ->
            val presenter = RecordingPresenter()
            val journey = Journey(emptyList())
            val vm = sit(journey, presenter, ExamWindows())
            journey.outcome = outcome
            vm.dispatch(PlayerContract.Intent.Completed(stars = 0, answer = "x", mistakes = 0, correct = false))
            advanceUntilIdle(); runCurrent()
            assertEquals(1, presenter.ended, "$outcome")
        }
    }

    @Test fun aPaperStillBeingSentKeepsItsActivityUntilItIsDelivered() = runTest {
        val presenter = RecordingPresenter()
        val journey = Journey(paper.stops.dropLast(1).map { it.id }, outcome = SubmitOutcome.QUEUED)
        val vm = sit(journey, presenter, ExamWindows())
        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceUntilIdle(); runCurrent()
        assertEquals(PlayerContract.Phase.SENDING, vm.state.value.phase)
        assertEquals(0, presenter.ended)
    }

    @Test fun aHomeworkShowsNothingOutsideTheApp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val presenter = RecordingPresenter()
        val homework = HotSoupSeed.lesson
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh(): List<Child> = listOf(maya)
            override suspend fun children(): List<Child> = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val vm = StopPlayerViewModel(homework.id, 1, 0, 0, Lessons(homework), Journey(emptyList()), children, FakeContentApi(FakeAuth(settings), delayMillis = 0), copy, sitting = presenter, windows = ExamWindows(), now = { now })
        built.add(vm); runCurrent()
        assertTrue(presenter.shown.isEmpty()); assertEquals(0, presenter.ended)
    }
}
