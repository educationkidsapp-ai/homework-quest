package quest.feature.journey

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.AttemptAck
import quest.api.dto.AttemptUpload
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
import quest.api.dto.ReleasedResult
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.StopCategory
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.MapRepository
import quest.feature.content.domain.PendingAnswersSync
import quest.feature.content.domain.ChildResult
import quest.feature.content.domain.ChildResultsUseCase
import quest.core.platform.ManualConnectivity
import quest.feature.content.domain.StopMediaRecord
import quest.feature.content.domain.SubmitOutcome
import quest.feature.journey.presentation.JourneyContract
import quest.feature.journey.presentation.JourneyViewModel
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.PlayerContract.Phase
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.journey.presentation.ExamResultViewModel
import quest.feature.journey.presentation.isExam
import quest.feature.journey.presentation.playFor
import quest.feature.map.presentation.ExamStatus
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.MapViewModel
import quest.feature.map.presentation.canSit
import quest.feature.map.presentation.examCard
import quest.feature.map.presentation.examStatus
import quest.feature.map.presentation.examTime
import quest.feature.map.presentation.examTimeLeft
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.domain.Streak
import quest.feature.school.domain.FlagStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1d, §8 — the rules of an exam sitting, on the view models that own them.
 *
 * Everything runs on one `StandardTestDispatcher`: `Dispatchers.Main` is that dispatcher, the repositories are fakes
 * that never leave it, and the pause after an answer is virtual time. No test waits on the wall clock, which is what
 * made the first version of this file fail on a slow runner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExamModeTest {

    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)

    /** The seed lesson sat as an exam over its level-2 play: the route's level (1) must not decide the paper. */
    private val exam: PublishedLesson = HotSoupSeed.lesson.let { it.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[1]) }
    private val paper: Play = exam.examPlay!!
    private val firstSingle = paper.stops.indexOfFirst { it.category == StopCategory.SINGLE }
    private val en = LessonStrings.en

    private class FakeChildren(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class FakeLessons(private val lesson: PublishedLesson) : LessonRepository {
        override suspend fun lesson(id: String, version: Int?): PublishedLesson = lesson
        override suspend fun cached(id: String): PublishedLesson? = lesson.takeIf { it.id == id }
        override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
        override suspend fun prefetch(map: MapResponse) {}
    }

    /** What was recorded, in memory, and whatever the "server" is set to answer to a hand-in. */
    private class FakeJourney(var outcome: SubmitOutcome = SubmitOutcome.SENT) : JourneyRepository {
        data class Recorded(val stopId: String, val stars: Int, val answer: String, val correct: Boolean, val attempt: Int)
        val recorded = mutableListOf<Recorded>()
        val wrongAttempts = mutableListOf<String>()
        var submits = 0
        var completed = 0
        val stars = mutableMapOf<String, Int>()

        override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int) = LevelProgress(lessonId, level, variant, stars.toMap(), null)
        override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) {
            recorded += Recorded(stopId, stars, answer, correct, attemptNumber); this.stars[stopId] = stars; unsent++
        }
        override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = emptyList()
        override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) { wrongAttempts += stopId }
        override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play): LevelProgress { completed++; return progress(childId, lesson.id, play.level, play.variant) }
        override suspend fun completions(childId: String): List<LessonCompletionInfo> = emptyList()
        override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = emptyMap()
        override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) {}
        override suspend fun flushAttempts(childId: String): Int = 0
        /** Answers the "server" has not taken: everything recorded since the last [SubmitOutcome.SENT]. */
        var unsent = 0
        override suspend fun submit(childId: String, lessonId: String): SubmitOutcome { submits++; if (outcome == SubmitOutcome.SENT || outcome == SubmitOutcome.ALREADY_TAKEN) unsent = 0; return outcome }
        override suspend fun pendingCount(childId: String, lessonId: String): Int = unsent
        override suspend fun pending(childId: String): Map<String, Int> = if (unsent > 0) mapOf(HotSoupSeed.lesson.id to unsent) else emptyMap()
        override suspend fun firstTryResults(childId: String, skillId: String, excludeLessons: Set<String>): List<Boolean> = emptyList()
        var report: ProgressResponse? = null
        override suspend fun progressReport(childId: String): ProgressResponse? = report
    }

    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
    private val media: ContentApi = FakeContentApi(FakeAuth(settings), delayMillis = 0)
    private val built = mutableListOf<ViewModel>()

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    /** `runTest` with `Dispatchers.Main` on the test's own scheduler, so a view model's `delay` is virtual. */
    private fun examTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block()
    }

    private fun TestScope.player(journey: FakeJourney, lesson: PublishedLesson = exam, startIndex: Int = 0, sync: PendingAnswersSync? = null): Pair<StopPlayerViewModel, MutableList<PlayerContract.Effect>> {
        val vm = StopPlayerViewModel(lesson.id, 1, 0, startIndex, FakeLessons(lesson), journey, FakeChildren(maya), media, copy, sync = sync).also { built.add(it) }
        val effects = mutableListOf<PlayerContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        return vm to effects
    }

    /** Every question before [index] already answered, so the sitting stands on [index]. */
    private fun answeredUpTo(index: Int) = FakeJourney().apply { paper.stops.take(index).forEach { stars[it.id] = 3 } }

    // ---------------------------------------------------------------- (e) the paper

    @Test fun anExamPlaysItsOwnPaperWhateverTheRouteSays() {
        assertTrue(exam.isExam)
        assertEquals(paper, exam.playFor(level = 1, variant = 0))
        assertEquals(paper, exam.playFor(level = 3, variant = 1))
        // A lesson typed "exam" without a paper is not one, and a homework keeps its levels.
        assertFalse(exam.copy(examPlay = null).isExam)
        assertEquals(HotSoupSeed.lesson.plays[0], HotSoupSeed.lesson.playFor(1, 0))
    }

    // ---------------------------------------------------------------- (a) one acknowledgement

    @Test fun aRightAndAWrongAnswerAreAcknowledgedIdentically() = examTest {
        val rightJourney = answeredUpTo(firstSingle); val wrongJourney = answeredUpTo(firstSingle)
        val (right, rightEffects) = player(rightJourney)
        val (wrong, wrongEffects) = player(wrongJourney)
        rightEffects.clear(); wrongEffects.clear()

        right.dispatch(PlayerContract.Intent.Correct(1, "a"))
        wrong.dispatch(PlayerContract.Intent.Wrong(1, "Look at the picture again.", null, "b"))
        runCurrent()

        // The same phase, the same words, no hint and no praise — nothing on screen tells the two apart.
        listOf(right, wrong).forEach { vm ->
            assertEquals(Phase.STEP_DONE, vm.state.value.phase)
            assertEquals("", vm.state.value.hint)
            assertEquals("", vm.state.value.praise)
            assertNull(vm.state.value.numberLine)
        }
        assertEquals(listOf<PlayerContract.Effect>(PlayerContract.Effect.Speak(en.answerSaved)), rightEffects)
        assertEquals(rightEffects, wrongEffects)

        // …and nothing in the timing does either: both leave the acknowledgement at the same instant.
        advanceTimeBy(StopPlayerViewModel.EXAM_ACKNOWLEDGE_MILLIS - 1); runCurrent()
        assertEquals(Phase.STEP_DONE, right.state.value.phase); assertEquals(Phase.STEP_DONE, wrong.state.value.phase)
        advanceTimeBy(1); runCurrent()
        assertEquals(right.state.value.phase, wrong.state.value.phase)
        assertEquals(right.state.value.index, wrong.state.value.index)
        assertTrue(right.state.value.index != firstSingle || right.state.value.phase == Phase.DONE)

        // What differs is only what the teacher's scorer reads.
        assertTrue(rightJourney.recorded.single().correct); assertFalse(wrongJourney.recorded.single().correct)
        assertEquals(0, wrongJourney.recorded.single().stars)
        assertEquals("b", wrongJourney.recorded.single().answer)
    }

    @Test fun aWrongAnswerIsFinal_noHintNoSecondGo() = examTest {
        val journey = answeredUpTo(firstSingle)
        val (vm, _) = player(journey)
        val stop = paper.stops[firstSingle]

        vm.dispatch(PlayerContract.Intent.Wrong(1, "A hint that must never show.", null, "b"))
        vm.dispatch(PlayerContract.Intent.TryAgain)                       // the hint sheet's button, if anything sent it
        vm.dispatch(PlayerContract.Intent.Correct(2, "a"))                 // a second tap on the same question
        vm.dispatch(PlayerContract.Intent.Wrong(2, "Another hint.", null, "c"))
        runCurrent()

        assertEquals(listOf(FakeJourney.Recorded(stop.id, 0, "b", false, 1)), journey.recorded)
        assertTrue(journey.wrongAttempts.isEmpty(), "an exam answer is the result, not a homework's wrong attempt")
        assertEquals(Phase.STEP_DONE, vm.state.value.phase)
        assertEquals(2, journey.submits, "once when the sitting opened (leftovers), once for the one answer that counted")
    }

    @Test fun aMultiPartQuestionFinishedWrongIsAcknowledgedTheSameWay() = examTest {
        val index = paper.stops.indexOfFirst { it.category != StopCategory.SINGLE }
        val journey = answeredUpTo(index)
        val (vm, effects) = player(journey)
        effects.clear()

        vm.dispatch(PlayerContract.Intent.Completed(stars = 0, answer = "x", mistakes = 0, correct = false))
        runCurrent()

        assertEquals(Phase.STEP_DONE, vm.state.value.phase)
        assertEquals(listOf<PlayerContract.Effect>(PlayerContract.Effect.Speak(en.answerSaved)), effects)
        assertFalse(journey.recorded.single().correct)
    }

    // ---------------------------------------------------------------- (b) resume, never reopen

    @Test fun aSittingResumesAtTheFirstUnansweredQuestion() = examTest {
        val journey = answeredUpTo(2)
        // The route still points at the first question — an old back-stack entry, or a tap that raced the answer.
        val (vm, _) = player(journey, startIndex = 0)
        assertEquals(2, vm.state.value.index)
        assertEquals(Phase.STOP, vm.state.value.phase)
        assertTrue(vm.state.value.exam)
    }

    @Test fun theOverviewOpensOnlyTheNextUnansweredQuestion() = examTest {
        val journey = answeredUpTo(2)
        val vm = JourneyViewModel(exam.id, 1, 0, FakeLessons(exam), journey, FakeChildren(maya), copy).also { built.add(it) }
        val effects = mutableListOf<JourneyContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        assertTrue(vm.state.value.exam)
        assertEquals(paper, vm.state.value.play)

        vm.dispatch(JourneyContract.Intent.TapStop(0))        // answered: stays shut
        vm.dispatch(JourneyContract.Intent.TapStop(3))        // not reached yet
        vm.dispatch(JourneyContract.Intent.SelectLevel(2))    // an exam has no level to choose
        runCurrent()
        assertTrue(effects.isEmpty())
        assertEquals(paper, vm.state.value.play)

        vm.dispatch(JourneyContract.Intent.TapStop(2))
        runCurrent()
        assertEquals(listOf<JourneyContract.Effect>(JourneyContract.Effect.OpenStop(exam.id, 1, 0, 2)), effects)
    }

    @Test fun aHomeworkStillReopensAnAnsweredStep() = examTest {
        val homework = HotSoupSeed.lesson
        val journey = FakeJourney().apply { homework.plays[0].stops.take(2).forEach { stars[it.id] = 3 } }
        val vm = JourneyViewModel(homework.id, 1, 0, FakeLessons(homework), journey, FakeChildren(maya), copy).also { built.add(it) }
        val effects = mutableListOf<JourneyContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        vm.dispatch(JourneyContract.Intent.TapStop(0)); runCurrent()
        assertEquals(listOf<JourneyContract.Effect>(JourneyContract.Effect.OpenStop(homework.id, 1, 0, 0)), effects)
    }

    // ---------------------------------------------------------------- (c) the 409s

    @Test fun aSecondSittingIsRefusedAndStops() = examTest {
        val journey = answeredUpTo(firstSingle).apply { outcome = SubmitOutcome.ALREADY_TAKEN }
        val (vm, effects) = player(journey)
        vm.dispatch(PlayerContract.Intent.Correct(1, "a"))
        advanceUntilIdle(); runCurrent()   // the effect collector lives in backgroundScope, which advanceUntilIdle does not wait for

        assertEquals(Phase.REFUSED, vm.state.value.phase)
        assertEquals(SubmitOutcome.ALREADY_TAKEN, vm.state.value.refusal)
        // It does not move on to the next question, and it does not pretend the paper was handed in.
        assertEquals(firstSingle, vm.state.value.index)
        assertEquals(0, journey.completed)
        assertTrue(effects.none { it is PlayerContract.Effect.Finished })
    }

    /** Rule (d): the window shutting mid-sitting is not an error screen — it ends on the submitted screen. */
    @Test fun theWindowClosingMidSittingEndsOnTheSubmittedScreen() = examTest {
        val journey = answeredUpTo(firstSingle).apply { outcome = SubmitOutcome.CLOSED; unsent = 0 }
        val (vm, effects) = player(journey)
        vm.dispatch(PlayerContract.Intent.Wrong(1, "", null, "b"))
        advanceUntilIdle(); runCurrent()

        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
        assertEquals(0, journey.completed, "a paper cut off by its window is not marked handed in — a re-opening can still finish it")
        assertEquals(firstSingle, vm.state.value.index, "and it does not move on to the next question")

        // The submitted screen then says the exam closed and that this one answer did not reach the teacher.
        val state = complete(journey)
        assertTrue(state.exam); assertTrue(state.examClosed)
        assertEquals(1, state.undelivered)
    }

    private suspend fun TestScope.complete(journey: FakeJourney): quest.feature.journey.presentation.CompleteContract.State {
        val rewards = object : RewardsRepository {
            override suspend fun stickers(): List<Sticker> = emptyList()
            override suspend fun addSticker(key: String): Sticker = Sticker("s", key, 0)
            override suspend fun streak(): Streak = Streak(0, null)
            override suspend fun saveStreak(streak: Streak) {}
        }
        val vm = quest.feature.journey.presentation.LessonCompleteViewModel(exam.id, 1, 0, FakeLessons(exam), journey, FakeChildren(maya),
            quest.feature.rewards.domain.AwardStickerUseCase(rewards, listOf("a")), quest.feature.rewards.domain.UpdateStreakUseCase(rewards), copy).also { built.add(it) }
        runCurrent()
        return vm.state.value
    }

    @Test fun anUnreachableServerKeepsTheSittingGoing() = examTest {
        val journey = answeredUpTo(firstSingle).apply { outcome = SubmitOutcome.QUEUED }
        val (vm, _) = player(journey)
        vm.dispatch(PlayerContract.Intent.Correct(1, "a"))
        advanceUntilIdle(); runCurrent()   // the effect collector lives in backgroundScope, which advanceUntilIdle does not wait for
        assertTrue(vm.state.value.phase == Phase.STOP || vm.state.value.phase == Phase.DONE)
        assertNull(vm.state.value.refusal)
    }

    @Test fun theRepositoryKeepsAnswersAClosedWindowRefusedAndDropsOnlyAnAlreadyTakenPaper() = runTest {
        var failure: Throwable? = null
        val uploads = mutableListOf<List<AttemptUpload>>()
        val fake = FakeContentApi(FakeAuth(settings), delayMillis = 0)
        val api = object : ContentApi by fake {
            override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck {
                failure?.let { throw it }
                uploads += attempts; return AttemptAck(attempts.size)
            }
        }
        val journey = JourneyRepositoryImpl(api, db)
        suspend fun answer(stop: Int) = journey.recordStop(maya.id, exam, paper, paper.stops[stop].id, 0, "x", false, 1, 1)

        // Not reached: the answer stays queued and goes up with the next one.
        answer(0); failure = ApiException(ApiError(ApiError.NETWORK, "offline"))
        assertEquals(SubmitOutcome.QUEUED, journey.submit(maya.id, exam.id))
        assertEquals(1, journey.pendingCount(maya.id, exam.id))
        answer(1); failure = null
        assertEquals(SubmitOutcome.SENT, journey.submit(maya.id, exam.id))
        assertEquals(2, uploads.single().size)
        assertEquals(0, journey.pendingCount(maya.id, exam.id))

        // 409 closed: said, and the answer is KEPT — a teacher's re-opening delivers it with the very same call.
        answer(2); failure = ApiException(ApiError(ApiError.EXAM_CLOSED, "not open"))
        assertEquals(SubmitOutcome.CLOSED, journey.submit(maya.id, exam.id))
        assertEquals(1, journey.pendingCount(maya.id, exam.id))
        assertEquals(mapOf(exam.id to 1), journey.pending(maya.id))
        failure = null                                                    // re-opened
        assertEquals(SubmitOutcome.SENT, journey.submit(maya.id, exam.id))
        assertEquals(paper.stops[2].id, uploads.last().single().stopId)
        assertEquals(0, journey.pendingCount(maya.id, exam.id))

        // 409 already taken: the server holds a handed-in paper; these answers will never be accepted and are dropped.
        answer(3); failure = ApiException(ApiError(ApiError.EXAM_ALREADY_TAKEN, "already handed in"))
        assertEquals(SubmitOutcome.ALREADY_TAKEN, journey.submit(maya.id, exam.id))
        assertEquals(0, journey.pendingCount(maya.id, exam.id))
    }

    /** A closed exam's kept answers must not hold a homework's answers on the device with them. */
    @Test fun aClosedExamDoesNotBlockOtherLessonsFromUploading() = runTest {
        val accepted = mutableListOf<String>()
        val fake = FakeContentApi(FakeAuth(settings), delayMillis = 0)
        val api = object : ContentApi by fake {
            override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck {
                if (attempts.any { it.lessonId == exam.id }) throw ApiException(ApiError(ApiError.EXAM_CLOSED, "not open"))
                accepted += attempts.map { it.lessonId }; return AttemptAck(attempts.size)
            }
        }
        val journey = JourneyRepositoryImpl(api, db)
        val homework = quest.api.samples.MathSeed.lesson
        journey.recordStop(maya.id, exam, paper, paper.stops[0].id, 0, "x", false, 1, 1)
        journey.recordStop(maya.id, homework, homework.plays[0], homework.plays[0].stops[0].id, 3, "y", true, 1, 0)

        assertEquals(1, journey.flushAttempts(maya.id))
        assertEquals(listOf(homework.id), accepted)
        assertEquals(mapOf(exam.id to 1), journey.pending(maya.id))
    }

    // ---------------------------------------------------------------- (d) handing in

    @Test fun theLastAnswerHandsThePaperIn() = examTest {
        val last = paper.stops.lastIndex
        val journey = answeredUpTo(last)
        val (vm, effects) = player(journey)
        assertEquals(last, vm.state.value.index)

        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceUntilIdle(); runCurrent()   // the effect collector lives in backgroundScope, which advanceUntilIdle does not wait for

        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(1, journey.completed)
        // The route's own level and variant come back, so the back stack pops the entry that was pushed.
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
    }

    @Test fun aPaperAlreadyAnsweredGoesStraightToSubmitted() = examTest {
        val journey = answeredUpTo(paper.stops.size)
        val (vm, effects) = player(journey)
        advanceUntilIdle(); runCurrent()   // the effect collector lives in backgroundScope, which advanceUntilIdle does not wait for
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
    }

    /** Review point 3: "submitted" is said only when the server has every answer. */
    @Test fun aFinishedPaperIsNotSubmittedWhileItsAnswersAreStillOnTheDevice() = examTest {
        val last = paper.stops.lastIndex
        val journey = answeredUpTo(last).apply { outcome = SubmitOutcome.QUEUED }
        val (vm, effects) = player(journey)
        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceUntilIdle(); runCurrent()

        assertEquals(Phase.SENDING, vm.state.value.phase)
        assertTrue(effects.none { it is PlayerContract.Effect.Finished }, "no submitted screen while the answers are unsent")
        assertEquals(0, journey.completed)

        vm.dispatch(PlayerContract.Intent.SendAgain)              // still offline
        advanceUntilIdle(); runCurrent()
        assertEquals(Phase.SENDING, vm.state.value.phase)

        journey.outcome = SubmitOutcome.SENT                      // back online
        vm.dispatch(PlayerContract.Intent.SendAgain)
        advanceUntilIdle(); runCurrent()
        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(1, journey.completed)
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
        val state = complete(journey)
        assertFalse(state.examClosed); assertEquals(0, state.undelivered)
    }

    /** M4 (D3): back online, the paper is handed in by itself — "Sending your answers…" waits for no tap. */
    @Test fun aPaperLeftSendingIsSubmittedByItselfWhenTheNetworkReturns() = examTest {
        val last = paper.stops.lastIndex
        val journey = answeredUpTo(last).apply { outcome = SubmitOutcome.QUEUED }
        val net = ManualConnectivity(initial = false)
        val sync = PendingAnswersSync(net, journey, FakeChildren(maya))
        sync.start(backgroundScope)
        val (vm, effects) = player(journey, sync = sync)
        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(Phase.SENDING, vm.state.value.phase)
        assertEquals(0, journey.completed)

        journey.outcome = SubmitOutcome.SENT                      // the network comes back; nobody taps anything
        net.set(true)
        advanceTimeBy(1_000); runCurrent()

        assertEquals(Phase.DONE, vm.state.value.phase)
        assertEquals(1, journey.completed, "handed in once, and only after the server took the answers")
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
    }

    @Test fun theWindowClosingOnUnsentAnswersSaysHowManyWereNotDelivered() = examTest {
        val last = paper.stops.lastIndex
        val journey = answeredUpTo(last).apply { outcome = SubmitOutcome.QUEUED; unsent = 2 }      // two earlier answers given offline
        val (vm, effects) = player(journey)
        vm.dispatch(PlayerContract.Intent.Completed(stars = 2, answer = "x", mistakes = 0, correct = true))
        advanceUntilIdle(); runCurrent()
        assertEquals(Phase.SENDING, vm.state.value.phase)

        journey.outcome = SubmitOutcome.CLOSED                    // the window shuts before the device is online again
        vm.dispatch(PlayerContract.Intent.SendAgain)
        advanceUntilIdle(); runCurrent()
        assertEquals(PlayerContract.Effect.Finished(exam.id, 1, 0), effects.last())
        assertEquals(0, journey.completed, "kept re-openable: not marked handed in")

        val state = complete(journey)
        assertTrue(state.examClosed)
        assertEquals(3, state.undelivered)
    }

    /** Review point 4: answers a closed window refused go up when the sitting is opened again, before any question. */
    @Test fun openingTheExamAgainSendsWhatWasLeftOnTheDeviceFirst() = examTest {
        val journey = answeredUpTo(2).apply { unsent = 1 }
        val (vm, _) = player(journey)
        assertEquals(1, journey.submits, "the leftover answer is sent on load")
        assertEquals(0, journey.unsent)
        assertEquals(2, vm.state.value.index)
    }

    // ---------------------------------------------------------------- (c) the window on the home card

    private val opens = 1_790_000_000_000L          // an arbitrary instant; the tests only move around it
    private val closes = opens + 3 * 60 * 60_000L
    private fun island(state: IslandState = IslandState.TODAY, window: ExamWindow? = ExamWindow(opens, closes)) =
        Island("i1", IslandKind.LESSON, LocalDate(2026, 10, 2), state, exam.title, Subject.ENGLISH, exam.id, examWindow = window)

    /**
     * Review point 1: the server is the authority. An island that carries a window is sittable now, whatever this
     * device's clock says — slow (before `opensAt`) or fast (past `closesAt`). The clock only words the countdown.
     */
    @Test fun anExamTheServerSentIsOpenWhateverTheDeviceClockSays() = examTest {
        val slow = opens - 6 * 3_600_000L
        val fast = closes + 6 * 3_600_000L
        listOf(slow, opens, closes - 1, closes, fast).forEach { clock ->
            assertTrue(examStatus(island(), clock).canSit, "open at device time $clock")
            assertEquals(listOf<MapContract.Effect>(MapContract.Effect.OpenLesson(exam.id, 1, 0)), home(island(), clock).second)
        }
        // Past the closing time the server's own window cannot be the reason it is open: no time is claimed.
        assertEquals(ExamStatus.REOPENED, examStatus(island(), closes))
        val card = examCard(island(), fast, TimeZone.UTC, en, emptyList(), true)
        assertEquals(en.examReopened, card.line); assertNull(card.left)
        // Handed in, and known only from the cache, stay shut — neither is the device clock's doing.
        assertEquals(ExamStatus.SUBMITTED, examStatus(island(IslandState.DONE), opens + 1))
        assertEquals(ExamStatus.UNAVAILABLE, examStatus(island(window = null), opens + 1))
    }

    @Test fun theCardSaysWhenItClosesAndRoughlyHowLongIsLeft() {
        val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val zone = TimeZone.UTC
        val open = examCard(island(), opens, zone, en, months, shortMonths = true)
        assertEquals(ExamStatus.OPEN, open.status)
        assertEquals(en.examOpenUntil.replace("{time}", examTime(closes, opens, zone, months, true)), open.line)
        assertEquals(en.examHoursLeft.replace("{n}", "3"), open.left)

        assertEquals(en.examHoursLeft.replace("{n}", "2"), examTimeLeft(120 * 60_000L, en))
        assertEquals(en.examOneHourLeft, examTimeLeft(119 * 60_000L, en))
        assertEquals(en.examOneHourLeft, examTimeLeft(60 * 60_000L, en))
        assertEquals(en.examMinutesLeft.replace("{n}", "59"), examTimeLeft(59 * 60_000L + 59_000, en))
        assertEquals(en.examMinutesLeft.replace("{n}", "10"), examTimeLeft(10 * 60_000L, en))
        assertEquals(en.examLastMinutes, examTimeLeft(10 * 60_000L - 1, en))

        // A time today is a clock time; another day carries its date.
        val noon = LocalDate(2026, 10, 2).toEpochDays() * 86_400_000L + 12 * 3_600_000L
        assertEquals("14:05", examTime(noon + 2 * 3_600_000L + 5 * 60_000L, noon, zone, months, true))
        assertEquals("3 Oct, 09:00", examTime(noon + 21 * 3_600_000L, noon, zone, months, true))

        // A submitted one shows no result.
        assertEquals(en.examSubmittedNote, examCard(island(IslandState.DONE), opens, zone, en, months, true).line)
        assertNull(examCard(island(IslandState.DONE), opens, zone, en, months, true).left)
    }

    private var mapLoads = 0

    private fun TestScope.home(island: Island, now: Long, journey: FakeJourney = FakeJourney()): Pair<MapViewModel, List<MapContract.Effect>> {
        val maps = object : MapRepository {
            override suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate): MapResponse {
                mapLoads++
                return MapResponse(child.id, HotSoupSeed.lesson.course, from, to, today, listOf(island))
            }
        }
        val rewards = object : RewardsRepository {
            override suspend fun stickers(): List<Sticker> = emptyList()
            override suspend fun addSticker(key: String): Sticker = Sticker("s", key, 0)
            override suspend fun streak(): Streak = Streak(0, null)
            override suspend fun saveStreak(streak: Streak) {}
        }
        val vm = MapViewModel(FakeChildren(maya), maps, journey, rewards, copy, FakeLessons(exam), now = { now }, childResults = ChildResultsUseCase(journey)).also { built.add(it) }
        val effects = mutableListOf<MapContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.dispatch(MapContract.Intent.Load); runCurrent()
        vm.dispatch(MapContract.Intent.TapIsland(island.id)); runCurrent()
        return vm to effects
    }

    @Test fun aSubmittedExamDoesNotOpenAgainAndSaysWhy() = examTest {
        val (vm, effects) = home(island(IslandState.DONE), opens + 1)
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.Speak(en.examAlreadyTaken)), effects)
        assertEquals(setOf(exam.id), vm.state.value.exams)
    }

    @Test fun aCachedExamWithoutAWindowIsStillAnExamAndStaysShut() = examTest {
        val (vm, effects) = home(island(window = null), opens + 1)
        assertEquals(setOf(exam.id), vm.state.value.exams)
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.Speak(en.examNeedsConnection)), effects)
    }

    // ---------------------------------------------------------------- M4: the released result (D4), "Continue" (D9)

    private val released = ReleasedResult(exam.id, exam.title, LocalDate(2026, 10, 2), Subject.ENGLISH, score = 60, band = "secure", comment = "Good effort, Hala.", releasedAt = 1_790_000_000_000L)
    private fun report(vararg results: ReleasedResult) = ProgressResponse(maya.id, emptyList(), emptyList(), 0, null, emptyList(), results.toList())

    @Test fun afterReleaseTheChildSeesHerResultOnTheCardAndOpensIt() = examTest {
        val journey = FakeJourney().apply { report = report(released) }
        val (vm, effects) = home(island(IslandState.DONE), opens + 1, journey)
        val marked = vm.state.value.marked.getValue(exam.id)
        assertEquals("secure", marked.band); assertEquals("Good effort, Hala.", marked.comment)
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.OpenResult(exam.id)), effects)

        val card = examCard(island(IslandState.DONE), opens + 1, TimeZone.UTC, en, emptyList(), true, marked = marked)
        assertEquals(ExamStatus.RELEASED, card.status)
        assertEquals("Marked by your teacher · Secure", card.line)
        assertFalse(card.line.contains("60"), "§7: no score out of 100 on a child's screen")
        assertEquals(en.examSeeResult, card.action)
        assertFalse(card.status.canSit, "a released exam is never sat again")
    }

    @Test fun beforeReleaseTheCardStillSaysTheTeacherWillShareIt() = examTest {
        val journey = FakeJourney().apply { report = report(released.copy(lessonId = "another-lesson")) }
        val (vm, effects) = home(island(IslandState.DONE), opens + 1, journey)
        assertTrue(vm.state.value.marked.isEmpty(), "only this page's exams, and only released ones")
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.Speak(en.examAlreadyTaken)), effects)
    }

    @Test fun theResultScreenShowsLevelAndComment_neverTheScore() = examTest {
        val journey = FakeJourney().apply { report = report(released) }
        val vm = ExamResultViewModel(exam.id, FakeChildren(maya), ChildResultsUseCase(journey), copy).also { built.add(it) }
        runCurrent()
        assertEquals(ChildResult(exam.id, exam.title, Subject.ENGLISH, LocalDate(2026, 10, 2), "secure", "Good effort, Hala.", released.releasedAt), vm.state.value.result)
        val missing = ExamResultViewModel("nope", FakeChildren(maya), ChildResultsUseCase(journey), copy).also { built.add(it) }
        runCurrent()
        assertNull(missing.state.value.result)
        assertFalse(missing.state.value.loading)
    }

    @Test fun aPartlySatExamSaysContinue_aFreshOneSaysStart() = examTest {
        val (fresh, _) = home(island(), opens + 1)
        assertTrue(exam.id !in fresh.state.value.startedExams)
        assertEquals(en.startExam, examCard(island(), opens + 1, TimeZone.UTC, en, emptyList(), true, started = false).action)

        val (partly, _) = home(island(), opens + 1, answeredUpTo(2))
        assertTrue(exam.id in partly.state.value.startedExams)
        assertEquals(en.continueExam, examCard(island(), opens + 1, TimeZone.UTC, en, emptyList(), true, started = true).action)
    }

    @Test fun pullingDownLoadsTheHomePageAgain() = examTest {
        val (vm, _) = home(island(), opens + 1)
        val before = mapLoads
        vm.dispatch(MapContract.Intent.Refresh); runCurrent()
        assertEquals(before + 1, mapLoads)
        assertFalse(vm.state.value.refreshing)
    }
}
