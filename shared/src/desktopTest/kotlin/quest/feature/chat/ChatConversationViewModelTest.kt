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
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.applyPresence
import quest.feature.chat.presentation.presenceLine
import quest.feature.parent.presentation.Strings
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
 * The open Messages conversation:
 *
 * 1. `/ws/chat` is **one socket per parent** and fans out every thread of hers — B6's complaints included, whose
 *    messages come from the same staff member — so every frame is matched by thread, never by sender alone (M8).
 * 2. A retried send resends the same words; a Messages send never carries a topic (B6), and a stray
 *    `400 complaint_moved` points the parent to the Complaints page.
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

        val sends = mutableListOf<String>()
        var history: List<ChatMessage> = emptyList()
        var failNextSend = false
        var failWith: Throwable = IllegalStateException("boom")
        var threadId = "th-ours"

        /** The parent's own rows — what a frame naming an unknown thread is looked up in (M7). */
        var rows: List<ChatThread> = emptyList()
        override suspend fun threads(childId: String): List<ChatThread> = rows
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

        override suspend fun uploadAttachment(childId: String, file: quest.feature.chat.domain.StagedUpload, onProgress: (Float) -> Unit): quest.api.dto.AttachmentRef = error("not used")
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage {
            sends.add(body)
            if (failNextSend) { failNextSend = false; throw failWith }
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
        ChatConversationViewModel(peer, chat, NoStaging).also { built.add(it) }

    private fun peer(threadId: String? = OURS) = ChatPeer(
        childId = "c1", staffId = COORDINATOR, staffName = "Ms. Lina",
        staffRole = ChatStaffRole.COORDINATOR, subject = "math", threadId = threadId,
    )

    private fun fromLina(id: String, thread: String) = ChatMessage(id, thread, ChatSender.TEACHER, COORDINATOR, "A word from Ms. Lina", 1_758_460_000_000L)

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

    /** M8: the reviewer's case — the same coordinator answering the parent's complaint must not land in Messages. */
    @Test fun aComplaintMessageFromTheSameStaffMemberNeverLandsInThisConversation() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Message(fromLina("m-cp", "cp-homework")))
        delay(80)
        assertTrue(vm.state.value.messages.isEmpty(), "a complaint's frame names the complaint, not this thread")

        chat.frames.emit(ChatFrame.Message(fromLina("m-ours", OURS)))
        vm.settle { it.messages.singleOrNull()?.id == "m-ours" }
    }

    @Test fun beforeAnyThreadExistsAStaffFrameIsAdoptedOnlyWhenMessagesListsIt() = runBlocking {
        val chat = FakeChat()
        val vm = viewModel(peer(threadId = null), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }
        assertNull(vm.state.value.threadId)

        chat.awaitCollector()
        chat.frames.emit(ChatFrame.Message(fromLina("m-cp", "cp-homework")))
        delay(80)
        assertNull(vm.state.value.threadId, "a complaint is not adopted as this conversation's thread")
        assertTrue(vm.state.value.messages.isEmpty())

        // Ms. Lina opens the Messages thread herself: the parent's rows list it, so it is ours.
        chat.rows = listOf(ChatThread(id = OURS, childId = "c1", childName = "Maya", teacherId = COORDINATOR, teacherName = "Ms. Lina", staffRole = ChatStaffRole.COORDINATOR))
        chat.frames.emit(ChatFrame.Message(fromLina("m-first", OURS)))
        vm.settle { it.threadId == OURS && it.messages.singleOrNull()?.id == "m-first" }
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

    // ---- 2. a retry resends the same words, and Messages never carries a topic

    @Test fun aRetriedMessageIsSentAgainAndTeachesTheThreadId() = runBlocking {
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

        assertEquals(listOf("When is the trip?", "When is the trip?"), chat.sends)
        assertEquals(OURS, vm.state.value.threadId, "the ack teaches the conversation its thread id")
        assertFalse(vm.state.value.complaintMoved)
    }

    /** M8: a stray `400 complaint_moved` keeps the message failed and points to the Complaints page. */
    @Test fun aComplaintMovedAnswerPointsToTheComplaintsPage() = runBlocking {
        val chat = FakeChat()
        chat.failNextSend = true
        chat.failWith = ApiException(ApiError(ApiError.COMPLAINT_MOVED, "Complaints are their own conversations."))
        val vm = viewModel(peer(), chat)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.settle { !it.loading }

        vm.dispatch(ChatConversationContract.Intent.UpdateInput("This is a complaint."))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.settle { it.complaintMoved }
        assertTrue(vm.state.value.messages.single().isFailed)
        assertTrue(Strings.en.complaints.movedNotice.isNotBlank() && Strings.ar.complaints.movedNotice.isNotBlank())
    }

    // ---- M4: presence (D6) and reading a thread that does not exist (D11)

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

/** For view-model tests that attach nothing: staging is never reached. */
internal object NoStaging : quest.feature.chat.domain.UploadStaging {
    override suspend fun stage(file: quest.api.UploadFile): quest.feature.chat.domain.StagedUpload? = null
    override fun discard(staged: quest.feature.chat.domain.StagedUpload) {}
}
