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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.datetime.LocalDate
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.LessonCompletionInfo
import quest.api.dto.MapResponse
import quest.api.dto.Play
import quest.api.dto.PracticePlay
import quest.api.dto.ProgressResponse
import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.StopCategory
import quest.api.samples.PhonicsSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.data.FakeContentApi
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.content.domain.LevelProgress
import quest.feature.content.domain.MapRepository
import quest.feature.content.domain.StopMediaRecord
import quest.feature.content.domain.SubmitOutcome
import quest.feature.journey.presentation.JourneyViewModel
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.PlayerContract.Phase
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.journey.presentation.playFor
import quest.feature.journey.presentation.smartHint
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.MapViewModel
import quest.feature.map.presentation.reviewVariant
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.rewards.domain.RewardsRepository
import quest.feature.rewards.domain.Sticker
import quest.feature.rewards.domain.Streak
import quest.feature.school.domain.FlagStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Feature 30 on the child's side: the teacher's graded hints in the stop player, and a review island that plays the
 * practice set her map points at. Nothing here asks anything of a model — the hints and the practice set arrive in the
 * published lesson, already approved, and the app only chooses between what it was given.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartHintsTest {

    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val en = LessonStrings.en
    private val today = LocalDate(2026, 9, 11)

    private val lesson = PhonicsSeed.lesson
    private val level1 = lesson.plays[0]
    private val singleIndex = level1.stops.indexOfFirst { it.category == StopCategory.SINGLE }
    private val single = level1.stops[singleIndex]

    private val nudge = "Say the word slowly. What is the first sound?"
    private val pointer = "Put a finger on your lips and make the quiet sound."
    private val hinted = lesson.copy(smartHints = mapOf(single.id to listOf(nudge, pointer)))

    /** The practice set as a teacher's approval publishes it: level 1, the first practice variant, four stops. */
    private val practicePlay = Play(1, Play.PRACTICE_VARIANT, level1.kind, level1.theme, PhonicsSeed.level1Variant.stops.filter { it.category == StopCategory.SINGLE }.take(4))
    private val withPractice = lesson.copy(practice = listOf(PracticePlay("sh-sound", "The sh sound", practicePlay)))

    // ---------------------------------------------------------------- the hint a wrong try earns

    @Test fun theFirstWrongTryGetsHintOneAndTheSecondHintTwo() = hintTest {
        val (vm, effects) = player(hinted)

        vm.dispatch(PlayerContract.Intent.Wrong(1, single.hintOrEmpty(), null, "b")); runCurrent()
        assertEquals(Phase.HINT, vm.state.value.phase)
        assertEquals(nudge, vm.state.value.hint)
        assertTrue(effects.contains(PlayerContract.Effect.Speak(nudge)), "the hint on screen is the hint read aloud")

        vm.dispatch(PlayerContract.Intent.TryAgain); runCurrent()
        vm.dispatch(PlayerContract.Intent.Wrong(2, single.hintOrEmpty(), null, "c")); runCurrent()
        assertEquals(pointer, vm.state.value.hint)

        // There is nothing after the stronger pointer: a third try keeps it rather than leaving the child with none.
        vm.dispatch(PlayerContract.Intent.TryAgain); runCurrent()
        vm.dispatch(PlayerContract.Intent.Wrong(3, single.hintOrEmpty(), null, "d")); runCurrent()
        assertEquals(pointer, vm.state.value.hint)
    }

    @Test fun aStopWithNoApprovedHintsKeepsItsOwnSingleHint() = hintTest {
        val (vm, _) = player(lesson)                                   // no `smartHints` at all, as every lesson is today
        vm.dispatch(PlayerContract.Intent.Wrong(1, "The stop's own hint.", null, "b")); runCurrent()
        assertEquals("The stop's own hint.", vm.state.value.hint)

        // …and so does a stop of a hinted lesson that she did not approve hints for.
        val (other, _) = player(lesson.copy(smartHints = mapOf("some-other-stop" to listOf(nudge, pointer))))
        other.dispatch(PlayerContract.Intent.Wrong(1, "The stop's own hint.", null, "b")); runCurrent()
        assertEquals("The stop's own hint.", other.state.value.hint)
    }

    @Test fun anExamShowsNoHintAtAllEvenIfOneSomehowReachedIt() = hintTest {
        // §8: the body of an exam carries no hints; were one ever to arrive, `hintsOff` and the player both refuse it.
        val exam = lesson.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = level1, smartHints = mapOf(single.id to listOf(nudge, pointer)))
        assertNull(exam.smartHint(single.id, 1))
        val (vm, _) = player(exam)
        vm.dispatch(PlayerContract.Intent.Wrong(1, "A hint that must never show.", null, "b")); runCurrent()
        assertEquals("", vm.state.value.hint)
        assertTrue(vm.state.value.phase != Phase.HINT)
    }

    // ------------------------------------------- post-merge review: the stops whose approvals were never drawn

    /**
     * The hints for a question inside an exit ticket are keyed by the question, which is what the teacher approved
     * them for. The player was looking them up by the ticket's own id, so every approval for a ticket question missed.
     */
    @Test fun anExitTicketQuestionGetsTheHintApprovedForThatQuestion() = hintTest {
        val ticket = level1.stops.filterIsInstance<quest.api.dto.Stop.ExitTicket>().first()
        val question = ticket.questions.first()
        val lessonWithTicketHints = lesson.copy(smartHints = mapOf(question.id to listOf(nudge, pointer)))
        val vm = StopPlayerViewModel(lesson.id, 1, 0, level1.stops.indexOf(ticket), FakeLessons(lessonWithTicketHints), FakeJourney(), FakeChildren(maya), media, copy).also { built += it }
        runCurrent()
        assertEquals(ticket.id, vm.state.value.stop?.id, "the player is on the ticket; the question is inside it")

        vm.dispatch(PlayerContract.Intent.Wrong(1, question.hintOrEmpty(), null, "b", stopId = question.id)); runCurrent()
        assertEquals(Phase.HINT, vm.state.value.phase)
        assertEquals(nudge, vm.state.value.hint)

        vm.dispatch(PlayerContract.Intent.TryAgain); runCurrent()
        vm.dispatch(PlayerContract.Intent.Wrong(2, question.hintOrEmpty(), null, "c", stopId = question.id)); runCurrent()
        assertEquals(pointer, vm.state.value.hint)

        // A stop that names nothing still means the stop on screen, which is every stop but a ticket's questions.
        vm.dispatch(PlayerContract.Intent.TryAgain); runCurrent()
        vm.dispatch(PlayerContract.Intent.Wrong(1, "The question's own hint.", null, "b")); runCurrent()
        assertEquals("The question's own hint.", vm.state.value.hint)
    }

    /**
     * A multi-answer stop (here the trace) is answered over several goes and corrects itself in place, so it reports a
     * mistake rather than a wrong answer. Its approved hints were published and never drawn at all.
     */
    @Test fun aMistakeOnAMultiAnswerStopDrawsTheApprovedHints() = hintTest {
        val trace = level1.stops.first { it.category == StopCategory.MULTI }
        val hintedTrace = lesson.copy(smartHints = mapOf(trace.id to listOf(nudge, pointer)))
        val (vm, effects) = playerAt(hintedTrace, level1.stops.indexOf(trace))

        vm.dispatch(PlayerContract.Intent.Mistake(trace.id, 1, "Start at the top.")); runCurrent()
        assertEquals(Phase.HINT, vm.state.value.phase)
        assertEquals(nudge, vm.state.value.hint)
        assertTrue(effects.contains(PlayerContract.Effect.Speak(nudge)))

        vm.dispatch(PlayerContract.Intent.TryAgain); runCurrent()
        vm.dispatch(PlayerContract.Intent.Mistake(trace.id, 2, "Start at the top.")); runCurrent()
        assertEquals(pointer, vm.state.value.hint)
    }

    @Test fun aMistakeWithNoApprovedHintsSaysWhatTheStopWouldHaveSaid() = hintTest {
        val trace = level1.stops.first { it.category == StopCategory.MULTI }
        val (vm, effects) = playerAt(lesson, level1.stops.indexOf(trace))

        vm.dispatch(PlayerContract.Intent.Mistake(trace.id, 1, "Start at the top.")); runCurrent()
        assertEquals(Phase.STOP, vm.state.value.phase, "nothing is drawn over a stop the child is still working at")
        assertTrue(effects.contains(PlayerContract.Effect.Speak("Start at the top.")))

        // An exam has no hints and no second go, so a mistake there says nothing at all.
        val exam = lesson.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = level1, smartHints = mapOf(trace.id to listOf(nudge, pointer)))
        val (sitting, examEffects) = playerAt(exam, level1.stops.indexOf(trace))
        examEffects.clear()
        sitting.dispatch(PlayerContract.Intent.Mistake(trace.id, 1, "Start at the top.")); runCurrent()
        assertTrue(sitting.state.value.phase != Phase.HINT)
        assertTrue(examEffects.none { it is PlayerContract.Effect.Speak })
    }

    // ---------------------------------------------------------------- the practice set a review island plays

    @Test fun aReviewIslandNamingAPracticeVariantOpensThatPracticePlay() = hintTest {
        val island = reviewIsland("${lesson.id}:1:${Play.PRACTICE_VARIANT}")
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.OpenLesson(lesson.id, 1, Play.PRACTICE_VARIANT)), tap(island))
        assertSame(practicePlay, withPractice.playFor(1, Play.PRACTICE_VARIANT))
    }

    @Test fun aReviewIslandNamingTheAgainPlayStillOpensIt() = hintTest {
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.OpenLesson(lesson.id, 1, 1)), tap(reviewIsland("${lesson.id}:1:1")))
        // An island from a server that knows nothing of practice sets, and one with no play at all, mean the same.
        assertEquals(listOf<MapContract.Effect>(MapContract.Effect.OpenLesson(lesson.id, 1, 1)), tap(reviewIsland(null)))
        assertEquals(1, reviewVariant("${lesson.id}:1:1")); assertEquals(1, reviewVariant(null)); assertEquals(1, reviewVariant("p"))
        assertEquals(3, reviewVariant("${lesson.id}:1:3"))
    }

    @Test fun aPracticeSetTheLessonNoLongerCarriesFallsBackToTheAgainPlay() {
        // She withdrew the practice set, or the map is newer than the body on this device: the review is the Again play.
        assertSame(lesson.variant, lesson.playFor(1, Play.PRACTICE_VARIANT))
        assertSame(withPractice.variant, withPractice.playFor(1, 5))
    }

    @Test fun thePracticeScreenNamesItsSkillAndOffersNoLevelToChoose() = hintTest {
        val vm = JourneyViewModel(lesson.id, 1, Play.PRACTICE_VARIANT, FakeLessons(withPractice), FakeJourney(), FakeChildren(maya), copy).also { built += it }
        runCurrent()
        assertEquals("The sh sound", vm.state.value.practiceSkill)
        assertSame(practicePlay, vm.state.value.play)

        // A level and the Again play are not practice, so the chooser stays where it is.
        val again = JourneyViewModel(lesson.id, 1, 1, FakeLessons(withPractice), FakeJourney(), FakeChildren(maya), copy).also { built += it }
        runCurrent()
        assertNull(again.state.value.practiceSkill)
    }

    /** The practice set's answers are recorded like any other play's — level 1, and the variant it was played at. */
    @Test fun aFinishedPracticeSetIsRecordedLikeEveryOtherPlay() = hintTest {
        val journey = FakeJourney()
        val vm = StopPlayerViewModel(lesson.id, 1, Play.PRACTICE_VARIANT, 0, FakeLessons(withPractice), journey, FakeChildren(maya), media, copy).also { built += it }
        runCurrent()
        assertSame(practicePlay, vm.state.value.play)
        repeat(practicePlay.stops.size) { vm.dispatch(PlayerContract.Intent.Correct(1, "a")); advanceUntilIdle() }
        assertEquals(practicePlay.stops.map { it.id }.toSet(), journey.recorded.map { it.stopId }.toSet())
        assertTrue(journey.recorded.all { it.level == 1 && it.variant == Play.PRACTICE_VARIANT })
    }

    // ---------------------------------------------------------------- the fake API, so the app runs without a server

    @Test fun theFakeApiServesApprovedHintsAndPointsTheReviewIslandAtThePracticeSet() = runTest {
        val db = Db(DriverFactory(null))
        val api = FakeContentApi(FakeAuth(SettingsStore(db)), delayMillis = 0, today = { today })
        val served = api.lesson(PhonicsSeed.LESSON_ID)
        assertTrue(served.smartHints.isNotEmpty())
        served.smartHints.values.forEach { assertEquals(2, it.size, "two graded hints, a nudge then a pointer") }
        val practice = served.practice.single()
        assertEquals("sh-sound", practice.skillId)
        assertEquals(Play.PRACTICE_VARIANT, practice.play.variant)
        assertTrue(practice.play.stops.size in Play.PRACTICE_STOPS)
        assertSame(practice.play, served.playFor(1, Play.PRACTICE_VARIANT))
        // Its stops are its own: shared ids would count a child's practice stars as the Again play's.
        assertTrue(practice.play.stops.none { s -> served.variant.stops.any { it.id == s.id } })
        // A seed with nothing approved still decodes, with both fields empty.
        assertTrue(api.lesson(quest.api.samples.HotSoupSeed.LESSON_ID).smartHints.isEmpty())
        assertTrue(api.lesson(quest.api.samples.HotSoupSeed.LESSON_ID).practice.isEmpty())
        // The exit ticket's own questions are hintable too, so the fake carries one for `p1-e1` inside `p1-exit`.
        assertTrue(served.smartHints.containsKey("p1-e1"))
    }

    /**
     * Post-merge review: only the lesson itself finishes a level. `AttemptService.deriveLessonCompletions` skips
     * `variant != 0` and `JourneyRepositoryImpl.completions` keeps only variant 0, so a practice set finished on the
     * fake must not draw the lesson's island as done either — a fake that says otherwise is a fake that lies.
     */
    @Test fun theFakeApisMapDoesNotCountAPracticeSetAsAFinishedLevel() = runTest {
        val db = Db(DriverFactory(null))
        val auth = FakeAuth(SettingsStore(db))
        val api = FakeContentApi(auth, delayMillis = 0, today = { today })
        auth.signIn("review@example.com", "secret123")
        val child = api.createChild(quest.api.dto.CreateChildRequest("Nour", "sun", Curriculum.BRITISH, 1))
        val practice = api.lesson(PhonicsSeed.LESSON_ID).practice.single().play

        // Every stop of the practice set finished with three stars — the whole set, and nothing of Level 1 itself.
        api.uploadAttempts(child.id, practice.stops.mapIndexed { i, stop ->
            quest.api.dto.AttemptUpload("pr-$i", stop.id, PhonicsSeed.LESSON_ID, practice.level, "\"a\"", true, 1, 0, 3, 0L)
        })

        val island = api.map(child.id, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)).islands
            .first { it.kind == IslandKind.LESSON && it.lessonId == PhonicsSeed.LESSON_ID }
        assertTrue(island.state != IslandState.DONE, "the lesson's island is not finished by a practice set")
        assertTrue(island.completedLevels.isNullOrEmpty(), "no level is completed: ${island.completedLevels}")
    }

    // ---------------------------------------------------------------- harness

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

    private fun hintTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        block()
    }

    private fun TestScope.player(lesson: PublishedLesson) = playerAt(lesson, singleIndex)

    private fun TestScope.playerAt(lesson: PublishedLesson, index: Int): Pair<StopPlayerViewModel, MutableList<PlayerContract.Effect>> {
        val vm = StopPlayerViewModel(lesson.id, 1, 0, index, FakeLessons(lesson), FakeJourney(), FakeChildren(maya), media, copy).also { built += it }
        val effects = mutableListOf<PlayerContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        runCurrent()
        return vm to effects
    }

    private fun reviewIsland(playId: String?) =
        Island("review-sh-sound", IslandKind.REVIEW, today, IslandState.TODAY, "The sh sound", lessonId = lesson.id, skillId = "sh-sound", playId = playId)

    private fun TestScope.tap(island: Island): List<MapContract.Effect> {
        val maps = object : MapRepository {
            override suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate) = MapResponse(child.id, lesson.course, from, to, today, listOf(island))
        }
        val rewards = object : RewardsRepository {
            override suspend fun stickers(): List<Sticker> = emptyList()
            override suspend fun addSticker(key: String): Sticker = Sticker("s", key, 0)
            override suspend fun streak(): Streak = Streak(0, null)
            override suspend fun saveStreak(streak: Streak) {}
        }
        val vm = MapViewModel(FakeChildren(maya), maps, FakeJourney(), rewards, copy, FakeLessons(withPractice)).also { built += it }
        val effects = mutableListOf<MapContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.dispatch(MapContract.Intent.Load); runCurrent()
        vm.dispatch(MapContract.Intent.TapIsland(island.id)); runCurrent()
        return effects
    }

    private fun quest.api.dto.Stop.hintOrEmpty(): String = (this as? quest.api.dto.Stop.SingleAnswer)?.hint ?: ""

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

    private class FakeJourney : JourneyRepository {
        data class Recorded(val stopId: String, val level: Int, val variant: Int, val stars: Int)
        val recorded = mutableListOf<Recorded>()
        val wrongAttempts = mutableListOf<Pair<String, Int>>()
        val stars = mutableMapOf<String, Int>()

        override suspend fun progress(childId: String, lessonId: String, level: Int, variant: Int) = LevelProgress(lessonId, level, variant, stars.toMap(), null)
        override suspend fun recordStop(childId: String, lesson: PublishedLesson, play: Play, stopId: String, stars: Int, answer: String, correct: Boolean, attemptNumber: Int, mistakes: Int, recording: ByteArray?, drawing: String?) {
            recorded += Recorded(stopId, play.level, play.variant, stars); this.stars[stopId] = stars
        }
        override suspend fun media(childId: String, lessonId: String): List<StopMediaRecord> = emptyList()
        override suspend fun recordWrongAttempt(childId: String, lesson: PublishedLesson, play: Play, stopId: String, answer: String, attemptNumber: Int) { wrongAttempts += stopId to attemptNumber }
        override suspend fun completeLevel(childId: String, lesson: PublishedLesson, play: Play) = progress(childId, lesson.id, play.level, play.variant)
        override suspend fun completions(childId: String): List<LessonCompletionInfo> = emptyList()
        override suspend fun parentUnlocks(childId: String): Map<String, List<Int>> = emptyMap()
        override suspend fun unlockLevel(childId: String, lessonId: String, level: Int) {}
        override suspend fun flushAttempts(childId: String): Int = 0
        override suspend fun submit(childId: String, lessonId: String): SubmitOutcome = SubmitOutcome.SENT
        override suspend fun pendingCount(childId: String, lessonId: String): Int = 0
        override suspend fun pending(childId: String): Map<String, Int> = emptyMap()
        override suspend fun firstTryResults(childId: String, skillId: String, excludeLessons: Set<String>): List<Boolean> = emptyList()
        override suspend fun progressReport(childId: String): ProgressResponse? = null
    }
}
