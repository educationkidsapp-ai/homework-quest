package quest.feature.broadcasts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.Child
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.UpdateChildRequest
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.presentation.BroadcastsContract
import quest.feature.broadcasts.presentation.BroadcastsViewModel
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.presentation.CoordinatorPickerContract
import quest.feature.chat.presentation.CoordinatorPickerViewModel
import quest.feature.chat.presentation.departmentWord
import quest.feature.chat.presentation.staffLabel
import quest.feature.children.domain.ChildrenRepository
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ContentApi
import quest.feature.content.data.FakeContentApi
import quest.feature.content.domain.MapRepository
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.parent.presentation.ParentHomeContract
import quest.feature.parent.presentation.ParentHomeViewModel
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.parent.presentation.Strings
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RM4: the two view models — the broadcasts feed (read marking, the flag-off state) and the picker that now offers the
 * department manager beside the coordinators.
 *
 * Both collect nothing forever, but both run their intent loop on `viewModelScope`, which is `Dispatchers.Main`. R8's
 * lesson: a scope left alive past `resetMain` dispatches onto a Main with no delegate and fails *the next* class, so
 * every view model built here is cancelled before Main is reset.
 */
class BroadcastsViewModelTest {

    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)

    private class FakeChildren(private val child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(child)
        override suspend fun children(): List<Child> = listOfNotNull(child)
        override suspend fun create(request: CreateChildRequest): Child = error("not used")
        override suspend fun update(id: String, request: UpdateChildRequest): Child = error("not used")
        override suspend fun delete(id: String) {}
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
        override suspend fun sectionName(childId: String): String? = "1A British"
        override suspend fun rememberSection(childId: String, name: String?) {}
    }

    private class FakeBroadcasts(
        var feed: BroadcastFeed = BroadcastFeed(),
        var failure: Throwable? = null,
    ) : BroadcastsRepository {
        val reads = mutableListOf<String>()
        override suspend fun feed(childId: String): BroadcastFeed = failure?.let { throw it } ?: feed
        override suspend fun markRead(childId: String, broadcastId: String): BroadcastView {
            reads += broadcastId
            return feed.items.first { it.id == broadcastId }.copy(read = true)
        }
    }

    /** Only the two lookups the picker makes; either may fail on its own. */
    private class FakePeers(
        var coordinators: Result<List<ChatThread>> = Result.success(emptyList()),
        var managers: Result<List<ChatThread>> = Result.success(emptyList()),
    ) : ChatRepository {
        override val connectionState = MutableStateFlow(ChatConnectionState.CONNECTED) as StateFlow<ChatConnectionState>
        override val incomingFrames = kotlinx.coroutines.flow.MutableSharedFlow<quest.api.dto.ChatFrame>()
        override suspend fun threads(childId: String): List<ChatThread> = emptyList()
        override suspend fun coordinators(childId: String): List<ChatThread> = coordinators.getOrThrow()
        override suspend fun managers(childId: String): List<ChatThread> = managers.getOrThrow()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?) = emptyList<quest.api.dto.ChatMessage>()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: quest.api.dto.ChatTopic?) = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) {}
        override suspend fun sendTyping(childId: String, teacherId: String) {}
        override fun connect() {}
        override fun disconnect() {}
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun feedViewModel(children: ChildrenRepository, repo: BroadcastsRepository) =
        BroadcastsViewModel(children, repo).also { built.add(it) }

    private fun pickerViewModel(children: ChildrenRepository, chat: ChatRepository) =
        CoordinatorPickerViewModel(children, chat).also { built.add(it) }

    private suspend fun <S> settle(state: StateFlow<S>, predicate: (S) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    private fun broadcast(id: String, kind: BroadcastKind = BroadcastKind.ANNOUNCEMENT, read: Boolean = false) =
        BroadcastView(
            id = id, kind = kind, authorId = "mg", authorName = "Ms. Nour", authorRole = ChatStaffRole.MANAGERIAL,
            title = "T-$id", bodyEn = "Body $id", curriculum = Curriculum.BRITISH, createdAt = 1_759_000_000_000L,
            read = read,
        )

    private fun row(id: String?, name: String, role: ChatStaffRole, subject: String? = null) = ChatThread(
        id = id, childId = "c1", childName = "Maya", teacherId = "s-$name", teacherName = name,
        className = "1A British", subject = subject, unread = 0, staffRole = role,
    )

    // ---- 1. the feed

    @Test fun theFeedIsLoadedAndGroupedWithItsUnreadCount() = runBlocking {
        val repo = FakeBroadcasts(BroadcastFeed(unread = 2, items = listOf(broadcast("a"), broadcast("b", BroadcastKind.EVENT))))
        val vm = feedViewModel(FakeChildren(maya), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(2, vm.state.value.unread)
        assertEquals(listOf("a"), vm.state.value.groups.announcements.map { it.id })
        assertEquals(listOf("b"), vm.state.value.groups.events.map { it.id })
        assertFalse(vm.state.value.notEnabled)
    }

    /** 404 is the `announcements` flag being off for this school, which is a state and not an error. */
    @Test fun aFourOhFourIsTheNotEnabledState() = runBlocking {
        val repo = FakeBroadcasts(failure = ApiException(ApiError(ApiError.NOT_FOUND, "no")))
        val vm = feedViewModel(FakeChildren(maya), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertTrue(vm.state.value.notEnabled)
        assertEquals(null, vm.state.value.errorMessage)
        assertTrue(vm.state.value.groups.isEmpty)
    }

    @Test fun anythingElseIsAnError() = runBlocking {
        val repo = FakeBroadcasts(failure = IllegalStateException("boom"))
        val vm = feedViewModel(FakeChildren(maya), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertFalse(vm.state.value.notEnabled)
        assertEquals("boom", vm.state.value.errorMessage)
    }

    /**
     * Tapping marks read and patches the row in place. It must not refetch: the feed is newest-first and a row that
     * arrived meanwhile would reorder the list under the parent's thumb.
     */
    @Test fun openingARowMarksItReadAndDropsTheUnreadCount() = runBlocking {
        val repo = FakeBroadcasts(BroadcastFeed(unread = 1, items = listOf(broadcast("a"))))
        val vm = feedViewModel(FakeChildren(maya), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertFalse(vm.state.value.groups.announcements.first().read)

        vm.dispatch(BroadcastsContract.Intent.Open("a"))
        settle(vm.state) { it.groups.announcements.firstOrNull()?.read == true }
        assertEquals(listOf("a"), repo.reads)
        assertEquals(0, vm.state.value.unread)
    }

    @Test fun theUnreadCountNeverGoesBelowZero() = runBlocking {
        val repo = FakeBroadcasts(BroadcastFeed(unread = 0, items = listOf(broadcast("a", read = true))))
        val vm = feedViewModel(FakeChildren(maya), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(BroadcastsContract.Intent.Open("a"))
        settle(vm.state) { repo.reads.isNotEmpty() }
        assertEquals(0, vm.state.value.unread)
    }

    @Test fun noChildMeansNoRequest() = runBlocking {
        val repo = FakeBroadcasts(BroadcastFeed(unread = 3, items = listOf(broadcast("a"))))
        val vm = feedViewModel(FakeChildren(null), repo)
        vm.dispatch(BroadcastsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertTrue(vm.state.value.groups.isEmpty)
        assertEquals(0, vm.state.value.unread)
    }

    // ---- 2. the picker: coordinators and the department manager, each surviving the other's failure

    @Test fun thePickerOffersBothSections() = runBlocking {
        val chat = FakePeers(
            coordinators = Result.success(listOf(row(null, "Ms. Lina", ChatStaffRole.COORDINATOR, "math"))),
            managers = Result.success(listOf(row(null, "Ms. Nour", ChatStaffRole.MANAGERIAL))),
        )
        val vm = pickerViewModel(FakeChildren(maya), chat)
        vm.dispatch(CoordinatorPickerContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(listOf("Ms. Lina"), vm.state.value.coordinators.map { it.teacherName })
        assertEquals(listOf("Ms. Nour"), vm.state.value.managers.map { it.teacherName })
        assertEquals(Curriculum.BRITISH, vm.state.value.curriculum)
        assertFalse(vm.state.value.isEmpty)
    }

    /** One endpoint failing must not empty the list the other answered — a school may have one and not the other. */
    @Test fun aManagerLookupThatFailsLeavesTheCoordinators() = runBlocking {
        val chat = FakePeers(
            coordinators = Result.success(listOf(row(null, "Ms. Lina", ChatStaffRole.COORDINATOR, "math"))),
            managers = Result.failure(ApiException(ApiError(ApiError.NOT_FOUND, "no"))),
        )
        val vm = pickerViewModel(FakeChildren(maya), chat)
        vm.dispatch(CoordinatorPickerContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(1, vm.state.value.coordinators.size)
        assertTrue(vm.state.value.managers.isEmpty())
        assertEquals(null, vm.state.value.errorMessage)
    }

    /** A 500 from one endpoint is not "this school has no coordinator" — the parent is told, not shown an empty list. */
    @Test fun aOneSidedServerErrorIsSurfaced() = runBlocking {
        val chat = FakePeers(
            coordinators = Result.failure(ApiException(ApiError("internal", "boom"))),
            managers = Result.success(listOf(row(null, "Ms. Nour", ChatStaffRole.MANAGERIAL))),
        )
        val vm = pickerViewModel(FakeChildren(maya), chat)
        vm.dispatch(CoordinatorPickerContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertTrue(vm.state.value.errorMessage != null)
        assertFalse(vm.state.value.childNotPlaced)
    }

    @Test fun bothFailingIsAnError() = runBlocking {
        val boom = ApiException(ApiError(ApiError.NETWORK, "offline"))
        val chat = FakePeers(coordinators = Result.failure(boom), managers = Result.failure(boom))
        val vm = pickerViewModel(FakeChildren(maya), chat)
        vm.dispatch(CoordinatorPickerContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertTrue(vm.state.value.isEmpty)
        assertTrue(vm.state.value.errorMessage != null)
    }

    @Test fun anUnplacedChildIsSaidSoRatherThanShownAsAFailure() = runBlocking {
        val notPlaced = ApiException(ApiError("child_not_placed", "no class"))
        val chat = FakePeers(coordinators = Result.failure(notPlaced), managers = Result.failure(notPlaced))
        val vm = pickerViewModel(FakeChildren(maya), chat)
        vm.dispatch(CoordinatorPickerContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertTrue(vm.state.value.childNotPlaced)
        assertEquals(null, vm.state.value.errorMessage)
    }

    // ---- 4. the parent home only asks for the count when the school bought the feature

    /** Counts the one call the badge makes; everything else is the ordinary fake. */
    private class CountingApi(private val delegate: ContentApi) : ContentApi by delegate {
        var feedCalls = 0
        override suspend fun childBroadcasts(childId: String) = run { feedCalls++; delegate.childBroadcasts(childId) }
    }

    private class TestAuth : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("p1", "parent@example.com"))
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(forceRefresh: Boolean): String = "mock-token"
    }

    private class Flagged(on: Boolean) : FlagStore {
        override val flags: StateFlow<Map<String, Boolean>> = MutableStateFlow(mapOf(Flags.ANNOUNCEMENTS to on))
    }

    /** The home wraps the calendar in `runCatching`, so a map that is not there is simply no lessons today. */
    private class NoMaps : MapRepository {
        override suspend fun map(child: Child, from: LocalDate, to: LocalDate, today: LocalDate): Nothing =
            error("no map in this test")
    }

    private fun countingApi() = CountingApi(FakeContentApi(TestAuth(), delayMillis = 0))

    private fun home(api: CountingApi, flags: FlagStore) =
        ParentHomeViewModel(FakeChildren(maya), CalendarUseCase(NoMaps()), TestAuth(), api, flags)
            .also { built.add(it) }

    /**
     * `announcements` is **off** in `DEFAULT_FLAGS`, so an ungated count would mean every school paid a refused
     * request on every home load and every child switch. The badge is zero either way; the request is the bug.
     */
    @Test fun theHomeDoesNotAskForTheCountWhileTheFlagIsOff() = runBlocking {
        val api = countingApi()
        val vm = home(api, Flagged(false))
        vm.dispatch(ParentHomeContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(0, api.feedCalls)
        assertEquals(0, vm.state.value.unreadBroadcasts)
    }

    @Test fun theHomeAsksOnceWhenTheFlagIsOn() = runBlocking {
        val api = countingApi()
        val vm = home(api, Flagged(true))
        vm.dispatch(ParentHomeContract.Intent.Load)
        settle(vm.state) { !it.loading }
        assertEquals(1, api.feedCalls)
        assertTrue(vm.state.value.unreadBroadcasts > 0)
    }

    // ---- 5. the manager's row reads for her department, not for the child's class

    @Test fun aManagerRowNamesTheDepartment() {
        val manager = row(null, "Ms. Nour", ChatStaffRole.MANAGERIAL)
        assertEquals("Department manager · British", staffLabel(manager, Strings.en, departmentWord(Curriculum.BRITISH, Strings.en)))
        assertEquals("مدير القسم · بريطاني", staffLabel(manager, Strings.ar, departmentWord(Curriculum.BRITISH, Strings.ar)))
        // Without the child's track the section name is still better than a bare role.
        assertEquals("Department manager · 1A British", staffLabel(manager, Strings.en, departmentWord(null, Strings.en)))
        // And a coordinator is untouched by any of it.
        assertEquals(
            "Subject coordinator · Math · 1A British",
            staffLabel(row(null, "Ms. Lina", ChatStaffRole.COORDINATOR, "math"), Strings.en, "British"),
        )
    }
}
