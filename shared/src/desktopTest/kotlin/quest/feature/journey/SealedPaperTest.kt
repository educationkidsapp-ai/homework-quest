package quest.feature.journey

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import quest.api.dto.Play
import quest.api.dto.PublishedLesson
import quest.api.dto.PublishedLessonSummary
import quest.api.dto.Stop
import quest.api.dto.StopCategory
import quest.api.dto.TapTask
import quest.api.dto.Hotspot
import quest.api.samples.MathSeed
import quest.api.samples.Seeds
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.json.AppJson
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.FakeExam
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.data.sealedForChild
import quest.feature.content.domain.LessonRepository
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.school.domain.FlagStore
import quest.ui.design.TestTags
import quest.ui.stops.LocalExamMode
import quest.ui.stops.StopContent
import quest.ui.stops.StopEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B3: before release an exam paper arrives **sealed** — `Play.sealed`, opaque ids, every key field a placeholder. The
 * app must decode it, draw every stop type without the key, sit it to "Submitted", answer with exactly the ids it was
 * sent, and claim nothing about right or wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class SealedPaperTest {
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)

    /** One stop of every type the contract has, taken from the seeds (and a read page with a tap task added). */
    private val everyType: List<Stop> = run {
        fun flat(s: Stop): List<Stop> = listOf(s) + ((s as? Stop.ExitTicket)?.questions?.flatMap(::flat) ?: emptyList())
        val all = Seeds.lessons.flatMap { l -> (l.plays + l.variant).flatMap { p -> p.stops.flatMap(::flat) } }
        val one = all.groupBy { it.type }.mapValues { it.value.first() }.toMutableMap()
        val page = all.filterIsInstance<Stop.ReadPage>().first()
        one["readPage"] = page.copy(tapTask = TapTask("Tap the pot.", listOf(Hotspot("h1", "pot", 0.1f, 0.1f, 0.2f, 0.2f), Hotspot("h2", "spoon", 0.5f, 0.5f, 0.2f, 0.2f)), listOf("h1")))
        one.values.sortedBy { it.type }
    }
    private val paper = Play(1, 0, MathSeed.lesson.plays[0].kind, MathSeed.lesson.plays[0].theme, everyType)
    private val exam: PublishedLesson = MathSeed.lesson.copy(id = "sealed-exam", type = "exam", hintsOff = true, numbersOff = true, examPlay = paper)

    /** What the server sends: sealed, as JSON, decoded the way the app decodes every body. */
    private val sent: PublishedLesson = AppJson.decodeFromString(PublishedLesson.serializer(), AppJson.encodeToString(PublishedLesson.serializer(), exam.sealedForChild()))
    private val sealedPaper = sent.examPlay!!

    @Test fun aSealedPaperOfEveryStopTypeDecodes_withNoKeyLeft() {
        assertEquals(22, sealedPaper.stops.map { it.type }.toSet().size, "every stop type of the contract is on the paper")
        assertEquals(true, sealedPaper.sealed)
        sealedPaper.stops.forEach { s ->
            assertEquals("", s.parentTip.en, s.type)
            when (s) {
                is Stop.SingleAnswer -> assertEquals("", s.hint, s.type)
                is Stop.MultiSelect -> assertTrue(s.correctIds.isEmpty())
                is Stop.SelectAll -> assertTrue(s.correctIds.isEmpty())
                is Stop.Order -> assertEquals(s.items.map { it.id }, s.correctOrder, "the placeholder is the order sent")
                is Stop.Retell -> assertEquals("", s.modelAnswer)
                is Stop.OpenAnswer -> assertEquals("", s.modelAnswer)
                else -> Unit
            }
        }
        assertTrue(sent.parentPanel.modelAnswers.isEmpty() && sent.parentPanel.stopTips.isEmpty())
        assertEquals(null, MathSeed.lesson.plays[0].sealed, "a homework stays as it is")
    }

    @Test fun everySealedStopDrawsInExamMode_withoutTheKey() = runComposeUiTest {
        val index = androidx.compose.runtime.mutableIntStateOf(0)
        setContent {
            LessonTheme(LessonStrings.en, rtl = false) {
                CompositionLocalProvider(LocalExamMode provides true) { StopContent(sealedPaper.stops[index.intValue], onEvent = {}) }
            }
        }
        sealedPaper.stops.indices.forEach { i -> index.intValue = i; waitForIdle() }
    }

    /** The order stop is sized by its cards: Check enables once every card is placed, and the answer is the ids sent. */
    @Test fun aSealedOrderStopTakesEveryCardAndAnswersWithTheIdsItWasSent() = runComposeUiTest {
        val order = sealedPaper.stops.filterIsInstance<Stop.Order>().single()
        val events = mutableListOf<StopEvent>()
        setContent {
            LessonTheme(LessonStrings.en, rtl = false) {
                CompositionLocalProvider(LocalExamMode provides true) { StopContent(order, onEvent = { events += it }) }
            }
        }
        onNodeWithTag(TestTags.STOP_CHECK).assertIsNotEnabled()
        order.items.forEach { item -> onNodeWithContentDescription(item.text).performClick() }
        onNodeWithTag(TestTags.STOP_CHECK).assertIsEnabled().performClick()
        val done = events.filterIsInstance<StopEvent.Completed>().single()
        assertEquals(order.items.map { it.id }.joinToString(","), done.answer)
    }

    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val uploads = mutableListOf<AttemptUpload>()
    private val fake = FakeContentApi(FakeAuth(settings), delayMillis = 0)
    private val api = object : ContentApi by fake {
        override suspend fun uploadAttempts(childId: String, attempts: List<AttemptUpload>): AttemptAck { uploads += attempts; return AttemptAck(attempts.size) }
    }
    private var vm: StopPlayerViewModel? = null
    @AfterTest fun tearDown() { vm?.viewModelScope?.cancel(); Dispatchers.resetMain() }

    private fun kotlinx.coroutines.test.TestScope.await(what: String, cond: () -> Boolean) {
        // The database works off the test's dispatcher, and the pause after an answer is virtual: move both along.
        repeat(500) { testScheduler.advanceTimeBy(200); runCurrent(); if (cond()) return; Thread.sleep(10) }
        error("never: $what")
    }

    @Test fun aSealedPaperIsSatToSubmitted_answeringWithTheIdsSent_claimingNothing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val journey = JourneyRepositoryImpl(api, db)
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(maya)
            override suspend fun refresh() = listOf(maya)
            override suspend fun children() = listOf(maya)
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val lessons = object : LessonRepository {
            override suspend fun lesson(id: String, version: Int?) = sent
            override suspend fun cached(id: String) = sent
            override suspend fun cachedSummaries(): List<PublishedLessonSummary> = emptyList()
            override suspend fun prefetch(map: MapResponse) {}
        }
        val copy = LessonCopy(ParentRepositoryImpl(settings), object : FlagStore { override val flags = MutableStateFlow(emptyMap<String, Boolean>()) })
        val player = StopPlayerViewModel(sent.id, 1, 0, 0, lessons, journey, children, api, copy).also { vm = it }
        val effects = mutableListOf<PlayerContract.Effect>()
        backgroundScope.launch { player.effects.collect { effects += it } }

        sealedPaper.stops.forEachIndexed { i, stop ->
            await("question ${i + 1} (${stop.type})") { player.state.value.phase == PlayerContract.Phase.STOP && player.state.value.index == i }
            when {
                // The first id sent is the placeholder "key": answering with it must claim nothing either.
                stop is Stop.SingleAnswer -> player.dispatch(PlayerContract.Intent.Correct(1, stop.optionIds.first()))
                stop is Stop.WriteSentence && stop.category == StopCategory.SINGLE -> player.dispatch(PlayerContract.Intent.Correct(1, stop.options!!.first()))
                stop is Stop.ExitTicket -> {
                    stop.questions.forEach { q -> player.dispatch(PlayerContract.Intent.QuestionAnswered(q.id, (q as? Stop.SingleAnswer)?.optionIds?.first() ?: "x", correct = true, stars = 3)) }
                    player.dispatch(PlayerContract.Intent.Completed(stars = 3, answer = "", mistakes = 0, correct = true))
                }
                stop is Stop.Order -> player.dispatch(PlayerContract.Intent.Completed(stars = 3, answer = stop.items.joinToString(",") { it.id }, mistakes = 0, correct = true))
                else -> player.dispatch(PlayerContract.Intent.Completed(stars = 3, answer = "x", mistakes = 0, correct = true))
            }
            await("the answer to ${stop.type} is acknowledged") { player.state.value.phase != PlayerContract.Phase.STOP || player.state.value.index != i }
        }
        await("submitted") { player.state.value.phase == PlayerContract.Phase.DONE && effects.lastOrNull() is PlayerContract.Effect.Finished }

        val byStop = uploads.associateBy { it.stopId }
        sealedPaper.stops.forEach { s -> assertTrue(s.id in byStop, "${s.type} was handed in") }
        val choice = sealedPaper.stops.filterIsInstance<Stop.Choice>().single()
        assertEquals(choice.options.first().id, byStop.getValue(choice.id).answerJson, "the id exactly as it was sent")
        assertTrue(uploads.none { it.correct || it.stars > 0 }, "a sealed paper's answers claim nothing — the server grades them")
    }

    @Test fun theFakeServersExamIsSealedToo() = runTest {
        val paper = fake.lesson(FakeExam.LESSON_ID).examPlay!!
        assertEquals(true, paper.sealed)
        assertTrue(paper.stops.filterIsInstance<Stop.SingleAnswer>().all { it.hint.isEmpty() })
    }
}
