package quest.feature.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.Resolver
import quest.feature.chat.domain.applyPresence
import quest.feature.chat.domain.resolverOf
import quest.feature.chat.presentation.presenceLine
import quest.feature.chat.presentation.resolvedBanner
import quest.feature.parent.presentation.Strings
import quest.api.dto.ChatPeerRole
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two defects the R8 review found in the open conversation:
 *
 * 1. `/ws/chat` is **one socket per parent** and fans out every thread of hers, so a `status` frame has to be matched
 *    against the open thread — otherwise a coordinator resolving the Math complaint flips the Resolved banner inside
 *    an open English conversation. The same holds for `read` and `typing`.
 * 2. A retried send has to carry the `topic` the original send went out with: the failed message is still in
 *    `messages`, so the complaint toggle is gone, and dropping the topic would file the complaint as a question.
 */
class ChatConversationViewModelTest {

    private val OURS = "th-ours"
    private val THEIRS = "th-theirs"
    private val COORDINATOR = "co-lina"

    /** Records every send, and fails the next one on demand, so a retry can be driven deterministically. */
    private class FakeChat : ChatRepository {
        val frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 32)
        override val connectionState = MutableStateFlow(ChatConnectionState.CONNECTED) as StateFlow<ChatConnectionState>
        override val incomingFrames: SharedFlow<ChatFrame> = frames

        val sends = mutableListOf<Pair<String, ChatTopic?>>()
        var history: List<ChatMessage> = emptyList()
        var failNextSend = false
        var threadId = "th-ours"

        override suspend fun threads(childId: String): List<ChatThread> = emptyList()
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        /**
         * `since` is honoured, because the view model uses it: every reconnect refetches from the last id it holds.
         * A fake that answered the whole history there would have the conversation append its own messages a second
         * time, which is a duplicate the real server never sends — and a flaky test rather than a real one.
         */
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> {
            if (since == null) return history
            val i = history.indexOfFirst { it.id == since }
            return if (i < 0) emptyList() else history.drop(i + 1)
        }

        override suspend fun uploadAttachment(childId: String, file: quest.api.UploadFile, onProgress: (Float) -> Unit): quest.api.dto.AttachmentRef = error("not used")
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic?, attachmentIds: List<String>): ChatMessage {
            sends.add(body to topic)
            if (failNextSend) { failNextSend = false; throw IllegalStateException("boom") }
            return ChatMessage("m-${sends.size}", threadId, ChatSender.PARENT, "p1", body, 1_758_450_000_000L)
        }

        var reads = 0
        override suspend fun markRead(childId: String, teacherId: String) { reads++ }
        override suspend fun sendTyping(childId: String, teacherId: String) {}
        override fun connect() {}
        override fun disconnect() {}
    }

    /**
     * The view model collects `incomingFrames` forever on `viewModelScope`, and that scope is `Dispatchers.Main`.
     * A collector left running past [tearDown] dispatches onto a Main that no longer has a delegate, which fails
     * *the next test class* rather than this one — `JoinSchoolTest` went red on CI that way. So every view model this
     * class builds is registered here and cancelled before Main is reset.
     */
    private val built = mutableListOf<ChatConversationViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(peer: ChatPeer, chat: ChatRepository) =
        ChatConversationViewModel(peer, chat).also { built.add(it) }

    private fun peer(threadId: String? = OURS, topic: ChatTopic = ChatTopic.QUESTION) = ChatPeer(
        childId = "c1", staffId = COORDINATOR, staffName = "Ms. Lina",
        staffRole = ChatStaffRole.COORDINATOR, subject = "math", topic = topic, threadId = threadId,
    )

    /**
     * `MutableSharedFlow` has no replay here, so a frame emitted before the view model's collector is subscribed is
     * dropped on the floor — a race that only shows up on a loaded runner. Every emit waits for the subscriber first.
     */
    private suspend fun FakeChat.awaitCollector() {
        repeat(400) {
            if (frames.subscriptionCount.value > 0) return
            delay(5)
        }
        error("the view model never subscribed to the frame stream")
    }

    private suspend fun ChatConversationViewModel.settle(predicate: (ChatConversationContract.State) -> Boolean) {
        repeat(400) {
            if (predicate(state.value)) return
            delay(5)
        }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    // ---- 1. a frame names its thread, and only that thread moves

    @Test fun aStatusFrameForAnotherThreadLeavesThisOneAlone() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertEquals(OURS, vm.state.value.threadId)

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Status(THEIRS, ChatThreadStatus.RESOLVED, 1_758_460_000_000L))
        delay(80)
        assertFalse(vm.state.value.resolved, "another thread's resolve must not raise this banner")

        chat.frames.emit(ChatFrame.Status(OURS, ChatThreadStatus.RESOLVED, 1_758_460_000_000L))
        vm.settle { it.resolved }

        // And re-opening ours lowers it again.
        chat.frames.emit(ChatFrame.Status(OURS, ChatThreadStatus.OPEN, 1_758_470_000_000L))
        vm.settle { !it.resolved }
    }

    @Test fun aStatusFrameCannotResolveAConversationThatHasNoThreadYet() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(threadId = null), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertNull(vm.state.value.threadId)

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Status(THEIRS, ChatThreadStatus.RESOLVED, 1L))
        delay(80)
        assertFalse(vm.state.value.resolved)
    }

    @Test fun readAndTypingFramesForAnotherThreadAreIgnoredToo() = runBlocking {
        val chat = FakeChat()
        chat.history = listOf(ChatMessage("m1", OURS, ChatSender.PARENT, "p1", "Hello", 1_758_450_000_000L))
        val vm = viewModel(peer(), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Typing(THEIRS, ChatSender.TEACHER))
        chat.frames.emit(ChatFrame.Read(THEIRS, ChatSender.TEACHER, 1_758_450_900_000L))
        delay(80)
        assertFalse(vm.state.value.isTeacherTyping, "another thread's typing must not show here")
        assertNull(vm.state.value.messages.single().readAt, "another thread's read must not tick this message")

        chat.frames.emit(ChatFrame.Read(OURS, ChatSender.TEACHER, 1_758_450_900_000L))
        vm.settle { it.messages.single().readAt != null }
    }

    // ---- 2. a retried first message is still the one that creates the thread

    @Test fun retryResendsTheComplaintTopic() = runBlocking {
        val chat = FakeChat()
        chat.failNextSend = true
        val vm = viewModel(peer(threadId = null), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        vm.dispatch(ChatConversationContract.Intent.ToggleComplaint)
        vm.settle { it.markAsComplaint }
        vm.dispatch(ChatConversationContract.Intent.UpdateInput("The homework is too long."))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.settle { it.messages.singleOrNull()?.isFailed == true }

        assertEquals(ChatTopic.COMPLAINT, chat.sends.single().second)
        // The toggle went off as the message left — the topic has to live on the message.
        assertFalse(vm.state.value.markAsComplaint)
        assertEquals(ChatTopic.COMPLAINT, vm.state.value.messages.single().topic)

        val clientId = vm.state.value.messages.single().clientId!!
        vm.dispatch(ChatConversationContract.Intent.RetrySend(clientId))
        vm.settle { it.messages.singleOrNull()?.isFailed == false && it.messages.single().isPending.not() }

        assertEquals(2, chat.sends.size)
        assertEquals(ChatTopic.COMPLAINT, chat.sends[1].second, "the retry must carry the complaint")
        assertEquals(ChatTopic.COMPLAINT, vm.state.value.topic)
        assertEquals(OURS, vm.state.value.threadId, "the ack teaches the conversation its thread id")
    }

    @Test fun aRetriedQuestionStaysAQuestion() = runBlocking {
        val chat = FakeChat()
        chat.failNextSend = true
        val vm = viewModel(peer(threadId = null), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        vm.dispatch(ChatConversationContract.Intent.UpdateInput("When is the trip?"))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.settle { it.messages.singleOrNull()?.isFailed == true }
        val clientId = vm.state.value.messages.single().clientId!!

        vm.dispatch(ChatConversationContract.Intent.RetrySend(clientId))
        vm.settle { it.messages.singleOrNull()?.isFailed == false && it.messages.single().isPending.not() }

        assertTrue(chat.sends.all { it.second == null }, "a question never sends a topic")
        assertEquals(ChatTopic.QUESTION, vm.state.value.topic)
    }

    /** M1: New message hands over "complaint" for a thread that does not exist yet — as the toggle, not as a badge. */
    @Test fun aComplaintChosenInNewMessageArrivesAsTheToggleAndIsSentOnTheFirstMessage() = runBlocking {
        val chat = FakeChat()
        val teacher = ChatPeer(childId = "c1", staffId = "t1", staffName = "Ms. Sara", staffRole = ChatStaffRole.TEACHER, subject = "math", threadId = null, startAsComplaint = true)
        val vm = viewModel(teacher, chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        assertEquals(ChatTopic.QUESTION, vm.state.value.topic, "nothing is a complaint until the server has taken the message")
        assertTrue(vm.state.value.markAsComplaint)
        assertTrue(vm.state.value.canMarkComplaint, "a teacher may be sent a complaint too")

        vm.dispatch(ChatConversationContract.Intent.UpdateInput("The homework was marked wrongly."))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.settle { it.topic == ChatTopic.COMPLAINT }
        assertEquals(ChatTopic.COMPLAINT, chat.sends.single().second)
    }

    /** An existing complaint thread opens as what it is: the badge, and no toggle. */
    @Test fun anExistingComplaintThreadKeepsItsTopic() {
        val vm = viewModel(peer(threadId = OURS, topic = ChatTopic.COMPLAINT).copy(startAsComplaint = true), FakeChat())
        assertEquals(ChatTopic.COMPLAINT, vm.state.value.topic)
        assertFalse(vm.state.value.markAsComplaint)
        assertFalse(vm.state.value.copy(loading = false).canMarkComplaint)
    }

    /** M1: an existing question thread can still become a complaint, and a resolved one is open again when it does. */
    @Test fun anExistingQuestionThreadBecomesAnOpenComplaint() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(threadId = OURS).copy(resolved = true), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertTrue(vm.state.value.canMarkComplaint)

        vm.dispatch(ChatConversationContract.Intent.ToggleComplaint)
        vm.settle { it.markAsComplaint }
        vm.dispatch(ChatConversationContract.Intent.UpdateInput("I would like this looked at formally."))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.settle { it.topic == ChatTopic.COMPLAINT }

        assertEquals(ChatTopic.COMPLAINT, chat.sends.last().second)
        assertFalse(vm.state.value.resolved)
        assertFalse(vm.state.value.canMarkComplaint, "it is a complaint now; there is nothing left to mark")
    }

    @Test fun theToggleDoesNotFlashWhileHistoryIsStillLoading() {
        val loading = ChatConversationContract.State(staffRole = ChatStaffRole.COORDINATOR, loading = true)
        assertFalse(loading.canMarkComplaint)
        assertTrue(loading.copy(loading = false).canMarkComplaint)
    }

    // ---- M4: presence (D6), the resolved banner (D7) and reading a thread that does not exist (D11)

    @Test fun presenceComesFromTheRowAndThePresenceFrame_notFromThisAppsSocket() = runBlocking {
        val chat = FakeChat()                                    // this app's socket is CONNECTED
        val vm = viewModel(peer().copy(peerOnline = false), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertEquals(false, vm.state.value.peerOnline, "the API said peerOnline:false — the header must not say Online")
        assertEquals(Strings.en.offline to false, presenceLine(vm.state.value, Strings.en))

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Presence(online = true, userId = "someone-else"))
        delay(80)
        assertEquals(false, vm.state.value.peerOnline, "another person's presence leaves this header alone")

        chat.frames.emit(ChatFrame.Presence(online = true, userId = COORDINATOR))
        vm.settle { it.peerOnline == true }
        assertEquals(Strings.en.online to true, presenceLine(vm.state.value, Strings.en))
    }

    @Test fun unknownPresenceShowsNoIndicator() = runBlocking {
        val vm = viewModel(peer(), FakeChat())
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertNull(vm.state.value.peerOnline)
        assertNull(presenceLine(vm.state.value, Strings.en))
    }

    @Test fun theResolvedBannerNamesWhoTheParentWroteTo() {
        val manager = ChatPeer("c1", "nour", "Ms. Nour", staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.MANAGERIAL)
        assertEquals(Resolver.MANAGER, resolverOf(manager.peerRole, manager.staffRole, manager.withAdmin))
        assertEquals(Strings.en.resolvedBannerManager, resolvedBanner(Resolver.MANAGER, Strings.en))
        assertFalse(resolvedBanner(Resolver.MANAGER, Strings.en).contains("coordinator"))
        assertEquals(Resolver.COORDINATOR, resolverOf(ChatPeerRole.COORDINATOR, ChatStaffRole.COORDINATOR, false))
        assertEquals(Resolver.TEACHER, resolverOf(null, ChatStaffRole.TEACHER, false), "an older server without peerRole falls back to the staff side")
        assertEquals(Resolver.ADMIN, resolverOf(null, ChatStaffRole.MANAGERIAL, withAdmin = true))
        assertTrue(resolvedBanner(Resolver.MANAGER, Strings.ar).isNotBlank())
    }

    @Test fun openingAThreadThatDoesNotExistYetDoesNotMarkItRead() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(threadId = null), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        delay(50)
        assertEquals(0, chat.reads, "no `…/read` for a thread the server has not created")

        val existing = FakeChat()
        val vm2 = viewModel(peer(), existing)
        vm2.dispatch(ChatConversationContract.Intent.Load)
        vm2.settle { !it.loading }
        delay(50)
        assertEquals(1, existing.reads)
    }

    @Test fun presenceFramesMoveOnlyTheRowsThatCarryPresence() {
        val row = ChatThread(id = "t", childId = "c1", childName = "Hala", teacherId = "maya", teacherName = "Maya", peerOnline = false)
        val silent = row.copy(id = "u", peerOnline = null)
        val moved = applyPresence(listOf(row, silent), "maya", true)
        assertEquals(true, moved[0].peerOnline)
        assertNull(moved[1].peerOnline)
        assertEquals(true, ChatPeer.of(moved[0]).peerOnline)
    }
}
