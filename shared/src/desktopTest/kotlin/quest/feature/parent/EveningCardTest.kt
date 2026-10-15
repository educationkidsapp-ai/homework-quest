package quest.feature.parent

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ApiError
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.EveningCard
import quest.api.dto.EveningCardSource
import quest.feature.broadcasts.domain.NoAttachmentDocuments
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.domain.SignOutUseCase
import quest.feature.content.data.FakeContentApi
import quest.feature.content.domain.MapRepository
import quest.feature.parent.presentation.EVENING_CARD_TAG
import quest.feature.parent.presentation.EveningCardSection
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.parent.presentation.ParentHomeContract
import quest.feature.parent.presentation.ParentHomeViewModel
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.LocalFlags
import quest.ui.design.ParentTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Feature 28 — the evening card on the parent home.
 *
 * Two halves. The **card** itself: her name put into the title and into the sentences the server wrote with `{child}`
 * in them, the right language, the assistant's byline only where the assistant actually wrote it, the tap that opens
 * the lesson, and nothing at all while the school does not have the flag. And the **home's load**: a school without
 * the card is never asked for one, and a 204, a 404 and a failed request are all simply no card — the home must not
 * break over the one thing on it that depends on a model answering.
 */
@OptIn(ExperimentalTestApi::class)
class EveningCardTest {

    private val maya = Child("c1", "Maya Haddad", "sun", Curriculum.BRITISH, 1)

    private val card = EveningCard(
        day = "2026-10-15",
        textAr = "اليوم عملت {child} على العدّ، وأتمّت الدرس. سار الأمر جيدًا.",
        textEn = "{child} worked on counting today and finished the lesson. It went well.",
        lessonIds = listOf("l1", "l2"),
        source = EveningCardSource.MODEL,
    )

    // ---- the card ---------------------------------------------------------------------------------------------

    private fun flags(on: Boolean) = object : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> =
            MutableStateFlow(DEFAULT_FLAGS + (Flags.PARENT_EVENING_CARD to on)).asStateFlow()
    }

    private fun section(
        card: EveningCard?,
        s: Strings = Strings.en,
        on: Boolean = true,
        childName: String? = maya.name,
        onLessonPanel: (String) -> Unit = { },
        assertions: ComposeUiTest.() -> Unit,
    ) = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalFlags provides flags(on)) {
                ParentTheme(rtl = s.isRtl) { EveningCardSection(card, childName, s, onLessonPanel) }
            }
        }
        assertions()
    }

    /** Her **first** name, the way a teacher says it — in the title and in every `{child}` the model wrote. */
    @Test fun theCardNamesHerInEnglish() = section(card) {
        onNodeWithText("Today with Maya").assertExists()
        onNodeWithText("Maya worked on counting today and finished the lesson. It went well.").assertExists()
    }

    @Test fun theCardNamesHerInArabic() = section(card, Strings.ar) {
        onNodeWithText("اليوم مع Maya").assertExists()
        onNodeWithText("اليوم عملت Maya على العدّ، وأتمّت الدرس. سار الأمر جيدًا.").assertExists()
        onNodeWithText("كتبها المساعد").assertExists()
    }

    /** The byline is a fact about who wrote the words, so it is shown only where the assistant did. */
    @Test fun onlyTheAssistantsOwnWordsCarryItsName() {
        section(card) { onNodeWithText(Strings.en.eveningCardByAssistant).assertExists() }
        section(card.copy(source = EveningCardSource.TEMPLATE)) {
            onNodeWithTag(EVENING_CARD_TAG).assertExists()
            onNodeWithText(Strings.en.eveningCardByAssistant).assertDoesNotExist()
        }
    }

    /** The server's 204, its 404 and a failed request all arrive here as null: no card, and no hole where one was. */
    @Test fun nothingIsDrawnWithoutACard() = section(null) {
        onNodeWithTag(EVENING_CARD_TAG).assertDoesNotExist()
    }

    /** §4: a school that has not bought the card never learns it exists, even with one in hand. */
    @Test fun theGateHidesItForASchoolWithoutTheFlag() = section(card, on = false) {
        onNodeWithTag(EVENING_CARD_TAG).assertDoesNotExist()
    }

    /** A child the school recorded no name for would read "Today with {name}"; she gets no card instead. */
    @Test fun aChildWithNoNameGetsNoCard() = section(card, childName = "  ") {
        onNodeWithTag(EVENING_CARD_TAG).assertDoesNotExist()
    }

    @Test fun tappingOpensTheFirstLessonTheCardIsAbout() {
        val opened = mutableListOf<String>()
        section(card, onLessonPanel = { opened += it }) {
            onNodeWithTag(EVENING_CARD_TAG).assertHasClickAction().performClick()
        }
        assertEquals(listOf("l1"), opened, "the first of the card's lessons, once")
    }

    // ---- the home's load --------------------------------------------------------------------------------------

    private class Kids(private val child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(child)
        override suspend fun children(): List<Child> = listOfNotNull(child)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class TestAuth : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("p1", "parent@example.com"))
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(forceRefresh: Boolean): String = "mock-token"
    }

    /** The home wraps the calendar in `runCatching`, so a map that is not there is simply no lessons today. */
    private class NoMaps : MapRepository {
        override suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate): Nothing =
            error("no map in this test")
    }

    /** Counts the asks and answers whatever the test wants — a card, the 204's null, or a refusal. */
    private class CardApi(delegate: ContentApi, private val answer: () -> EveningCard?) : ContentApi by delegate {
        var calls = 0
        override suspend fun eveningCard(childId: String): EveningCard? = run { calls++; answer() }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun home(api: ContentApi, on: Boolean) =
        ParentHomeViewModel(Kids(maya), CalendarUseCase(NoMaps()), SignOutUseCase(TestAuth(), Kids(maya), NoAttachmentDocuments), api, flags(on))
            .also { built.add(it) }

    private suspend fun <S> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    private fun loaded(answer: () -> EveningCard?, on: Boolean): Pair<ParentHomeContract.State, Int> = runBlocking {
        val api = CardApi(FakeContentApi(TestAuth(), delayMillis = 0), answer)
        val vm = home(api, on)
        vm.dispatch(ParentHomeContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.state.value to api.calls
    }

    @Test fun theHomeCarriesTodaysCard() {
        val (state, calls) = loaded({ card }, on = true)
        assertEquals(card, state.eveningCard)
        assertEquals(1, calls, "asked once per load")
    }

    /**
     * `parentEveningCard` is **off** in `DEFAULT_FLAGS`. An ungated ask would mean every other school paid a refused
     * request on every home load and every child switch — the bug `announcements` already taught once.
     */
    @Test fun theHomeDoesNotAskWhileTheFlagIsOff() {
        val (state, calls) = loaded({ card }, on = false)
        assertEquals(0, calls)
        assertNull(state.eveningCard)
    }

    @Test fun theTwoHundredAndFourIsSimplyNoCard() {
        val (state, _) = loaded({ null }, on = true)
        assertNull(state.eveningCard)
        assertEquals(maya, state.current, "and the rest of the home loaded")
    }

    /** A 404 (the flag flipped off, or not her child) or a dead connection: no card, and the home still loads. */
    @Test fun aRefusalNeverBreaksTheHome() {
        val (state, _) = loaded({ throw ApiException(ApiError(ApiError.NOT_FOUND, "no card here")) }, on = true)
        assertNull(state.eveningCard)
        assertEquals(maya, state.current)
        assertEquals(false, state.loading)
    }
}
