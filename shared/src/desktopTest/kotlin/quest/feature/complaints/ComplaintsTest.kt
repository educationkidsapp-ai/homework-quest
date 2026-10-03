package quest.feature.complaints

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.ApiException
import quest.api.dto.ApiError
import org.jetbrains.skia.Image
import quest.api.UploadFile
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatFrame
import quest.feature.chat.contains
import quest.feature.chat.data.FileUploadStaging
import quest.feature.chat.data.readBytes
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.AttachmentUploader
import quest.feature.chat.domain.PHOTO_MAX_PX
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.StagedUpload
import quest.feature.chat.exifMarker
import quest.feature.chat.gpsTag
import quest.feature.chat.photoWithGps
import quest.feature.chat.presentation.photoToUpload
import java.nio.file.Files
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatSender
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Child
import quest.api.dto.Complaint
import quest.api.dto.ComplaintActor
import quest.api.dto.ComplaintEvent
import quest.api.dto.Curriculum
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.complaints.data.ComplaintsRepositoryImpl
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.domain.RecipientGroup
import quest.feature.complaints.domain.TimelineItem
import quest.feature.complaints.domain.applyStatus
import quest.feature.complaints.domain.complaintOf
import quest.feature.complaints.domain.groupOf
import quest.feature.complaints.domain.timeline
import quest.feature.complaints.presentation.ComplaintContract
import quest.feature.complaints.presentation.ComplaintViewModel
import quest.feature.complaints.presentation.ComplaintsContract
import quest.feature.complaints.presentation.ComplaintsViewModel
import quest.feature.complaints.presentation.NewComplaintContract
import quest.feature.complaints.presentation.NewComplaintViewModel
import quest.feature.complaints.presentation.eventLine
import quest.feature.complaints.presentation.recipientLabel
import quest.core.text.isolate
import quest.feature.content.data.FakeContentApi
import quest.feature.notifications.domain.ParentBadges
import quest.feature.parent.presentation.Strings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8 — the Complaints page (the owner, 2026-10-03: "Complaints must be separate from messages"), on B6's contract and
 * against the fake server's rules: the list and its filter, New complaint, the conversation with its status lines, and
 * a parent who may reopen but never resolve.
 */
class ComplaintsTest {
    private object Auth : AuthProvider {
        override val state: StateFlow<AuthState> = MutableStateFlow(AuthState.SignedIn("p1", "parent@example.com"))
        override suspend fun signIn(email: String, password: String) {}
        override suspend fun signOut() {}
        override suspend fun idToken(forceRefresh: Boolean): String = "token"
    }

    private class Kids(vararg val list: Child) : ChildrenRepository {
        override val currentChild = MutableStateFlow<Child?>(list.firstOrNull())
        override suspend fun refresh() = list.toList()
        override suspend fun children() = list.toList()
        override suspend fun select(id: String) { currentChild.value = list.first { it.id == id } }
        override suspend fun clear() { currentChild.value = null }
    }

    /** The socket: frames are emitted by the test; nothing else is asked of it here. */
    private class Socket : ChatRepository {
        val frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 32)
        override val connectionState: StateFlow<ChatConnectionState> = MutableStateFlow(ChatConnectionState.CONNECTED)
        override val incomingFrames: SharedFlow<ChatFrame> = frames
        override suspend fun threads(childId: String) = emptyList<ChatThread>()
        override suspend fun coordinators(childId: String) = emptyList<ChatThread>()
        override suspend fun managers(childId: String) = emptyList<ChatThread>()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?) = emptyList<ChatMessage>()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage = error("Messages is not used here")
        override suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("complaints upload through their own uploader")
        override suspend fun markRead(childId: String, teacherId: String) = Unit
        override suspend fun sendTyping(childId: String, teacherId: String) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit

        suspend fun awaitCollector() {
            repeat(400) { if (frames.subscriptionCount.value > 0) return; delay(5) }
            error("nobody subscribed to the frames")
        }
    }

    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val omar = Child("c2", "Omar", "moon", Curriculum.BRITISH, 3)
    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    /** One fake server: the complaints routes and M7's upload route share its store, as the real server's do. */
    private val api = FakeContentApi(Auth, delayMillis = 0)
    private fun repo(): ComplaintsRepository = ComplaintsRepositoryImpl(api)

    /** What the uploader sent, byte for byte — read back from the staged file, as the real upload streams it. */
    private val uploaded = mutableListOf<UploadFile>()
    private val uploader = AttachmentUploader { childId, file, onProgress ->
        val bytes = file.readBytes()
        uploaded += UploadFile(file.name, file.contentType, bytes)
        api.uploadChatAttachment(childId, UploadFile(file.name, file.contentType, bytes)).also { onProgress(1f) }
    }
    private val staging = FileUploadStaging(directory = { Files.createTempDirectory("m8-complaints").toString() })

    private suspend fun <S> settle(state: StateFlow<S>, predicate: (S) -> Boolean): S {
        repeat(400) { if (predicate(state.value)) return state.value; delay(5) }
        error("state never satisfied the predicate; last was ${state.value}")
    }

    // ---- the list

    @Test fun theListShowsTheCurrentChildsComplaintsAndFiltersWithoutChangingTheCounts() = runBlocking {
        val vm = ComplaintsViewModel(Kids(maya, omar), repo(), Socket()).also { built += it }
        vm.dispatch(ComplaintsContract.Intent.Load)
        val all = settle(vm.state) { !it.loading }
        assertEquals("c1", all.childId)
        assertEquals(2, all.complaints.size)
        assertEquals(1, all.open)
        assertEquals(1, all.resolved)
        assertEquals("cp-bus", all.complaints.first().id, "the newest activity comes first")

        vm.dispatch(ComplaintsContract.Intent.SetFilter(ComplaintsContract.Filter.RESOLVED))
        val resolved = settle(vm.state) { !it.loading && it.filter == ComplaintsContract.Filter.RESOLVED }
        assertEquals(listOf("cp-homework"), resolved.complaints.map { it.id })
        assertEquals(1, resolved.open, "the counts cover every complaint whatever the filter")
    }

    @Test fun aStatusFrameMovesItsRowAndOnlyItsRow() = runBlocking {
        val socket = Socket()
        val vm = ComplaintsViewModel(Kids(maya), repo(), socket).also { built += it }
        vm.dispatch(ComplaintsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        socket.awaitCollector()
        socket.frames.emit(ChatFrame.Status("th-a-messages-thread", ChatThreadStatus.RESOLVED, 1L))
        delay(80)
        assertEquals(ChatThreadStatus.OPEN, vm.state.value.complaints.first { it.id == "cp-bus" }.status)

        val rows = vm.state.value.complaints
        val moved = applyStatus(rows, "cp-bus", ChatThreadStatus.RESOLVED, 5L)
        assertEquals(ChatThreadStatus.RESOLVED, moved.first { it.id == "cp-bus" }.status)
        assertEquals(5L, moved.first { it.id == "cp-bus" }.resolvedAt)
        assertEquals(rows.first { it.id == "cp-homework" }, moved.first { it.id == "cp-homework" })
        assertNull(applyStatus(moved, "cp-bus", ChatThreadStatus.OPEN, 6L).first { it.id == "cp-bus" }.resolvedAt)
    }

    @Test fun switchingTheChildReadsHerComplaints() = runBlocking {
        val kids = Kids(maya, omar)
        val vm = ComplaintsViewModel(kids, repo(), Socket()).also { built += it }
        vm.dispatch(ComplaintsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(ComplaintsContract.Intent.SelectChild("c2"))
        val after = settle(vm.state) { it.childId == "c2" && !it.loading }
        assertEquals("c2", kids.currentChild.value?.id, "the child is switched for the whole parent area, as elsewhere")
        assertTrue(after.complaints.all { it.childId == "c2" })
    }

    // ---- New complaint

    @Test fun aNewComplaintNeedsSomeoneASubjectAndAMessage_thenOpensItself() = runBlocking {
        val vm = NewComplaintViewModel(Kids(maya), repo(), staging, uploader).also { built += it }
        vm.dispatch(NewComplaintContract.Intent.Load)
        val loaded = settle(vm.state) { !it.loading }
        assertEquals(
            mapOf(RecipientGroup.TEACHER to 2, RecipientGroup.COORDINATOR to 2, RecipientGroup.MANAGER to 1),
            loaded.recipients.groupingBy { groupOf(it.peerRole) }.eachCount(),
            "her teachers, the coordinators of the child's subjects and the department manager",
        )
        assertFalse(loaded.canSend)

        vm.dispatch(NewComplaintContract.Intent.SelectRecipient("mg-nour"))
        vm.dispatch(NewComplaintContract.Intent.SetTitle("Pick-up queue"))
        assertFalse(settle(vm.state) { it.title.isNotEmpty() }.canSend, "a complaint needs its first message")
        vm.dispatch(NewComplaintContract.Intent.SetBody("The queue at the gate takes 30 minutes."))
        assertTrue(settle(vm.state) { it.body.isNotEmpty() }.canSend)

        vm.dispatch(NewComplaintContract.Intent.Send)
        val created = withTimeout(5_000) { vm.effects.first() } as NewComplaintContract.Effect.Created
        assertEquals("Pick-up queue", created.complaint.title)
        assertEquals("mg-nour", created.complaint.recipientId)
        assertEquals(ChatThreadStatus.OPEN, created.complaint.status)
    }

    @Test fun theSubjectLineIsCappedAtTheServersLimit() = runBlocking {
        val vm = NewComplaintViewModel(Kids(maya), repo(), staging, uploader).also { built += it }
        vm.dispatch(NewComplaintContract.Intent.SetTitle("x".repeat(200)))
        assertEquals(ComplaintsRepository.TITLE_MAX, settle(vm.state) { it.title.isNotEmpty() }.title.length)
    }

    // ---- one complaint

    @Test fun theConversationInterleavesMessagesAndStatusLines() = runBlocking {
        val vm = ComplaintViewModel("c1", "cp-homework", repo(), Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        val state = settle(vm.state) { !it.loading }
        val kinds = state.items.map { if (it is TimelineItem.Event) "event" else (it as TimelineItem.Message).message.id }
        assertEquals(listOf("m-hw-1", "m-hw-2", "event"), kinds, "Ms. Lina answered, then resolved it")
        assertTrue(state.resolved)
        assertTrue(state.showReopen)
        val line = eventLine((state.items.last() as TimelineItem.Event).event, Strings.en)
        assertTrue(line.startsWith("Resolved by ${isolate("Ms. Lina")} · "), line)
    }

    /** The parent reopens; she is never offered Resolve — the contract has no way for her to ask for it. */
    @Test fun theParentMayReopenButNeverResolve() = runBlocking {
        val vm = ComplaintViewModel("c1", "cp-homework", repo(), Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(ComplaintContract.Intent.Reopen)
        val reopened = settle(vm.state) { it.complaint?.status == ChatThreadStatus.OPEN && it.events.size == 2 }
        assertFalse(reopened.showReopen, "an open complaint offers nothing to press")
        val last = reopened.events.last()
        assertEquals(ComplaintActor.PARENT, last.by)
        assertTrue(eventLine(last, Strings.en).startsWith("Reopened by you · "))
        assertTrue(eventLine(last, Strings.ar).startsWith("أُعيد فتحها بواسطة أنت"))

        val open = ComplaintViewModel("c1", "cp-bus", repo(), Socket(), staging, uploader).also { built += it }
        open.dispatch(ComplaintContract.Intent.Load)
        assertFalse(settle(open.state) { !it.loading }.showReopen)
    }

    @Test fun aReplyIsSentAndOnlyThisComplaintsFramesLandHere() = runBlocking {
        val socket = Socket()
        val vm = ComplaintViewModel("c1", "cp-bus", repo(), socket, staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        val before = settle(vm.state) { !it.loading }.messages.size

        vm.dispatch(ComplaintContract.Intent.SetInput("Thank you, that would help."))
        vm.dispatch(ComplaintContract.Intent.Send)
        settle(vm.state) { s -> s.messages.size == before + 1 && s.messages.none { it.pending } }

        socket.awaitCollector()
        // The same staff member writing in a Messages thread, or in another complaint, is not this conversation.
        socket.frames.emit(ChatFrame.Message(ChatMessage("x1", "th-messages", ChatSender.TEACHER, "mg-nour", "About the trip", 2L)))
        socket.frames.emit(ChatFrame.Message(ChatMessage("x2", "cp-homework", ChatSender.TEACHER, "co-lina", "Another complaint", 2L)))
        delay(80)
        assertEquals(before + 1, vm.state.value.messages.size)

        socket.frames.emit(ChatFrame.Message(ChatMessage("x3", "cp-bus", ChatSender.TEACHER, "mg-nour", "The route changes on Monday.", 3L)))
        assertTrue(settle(vm.state) { s -> s.messages.any { it.message.id == "x3" } }.messages.last().message.body.startsWith("The route"))
    }

    /** Review of #209: a Reopen that does not reach the server is said, and nothing else moves. */
    @Test fun aFailedReopenIsSaidAndChangesNothing() = runBlocking {
        val failing = object : ComplaintsRepository by repo() {
            override suspend fun reopen(childId: String, complaintId: String): Complaint = throw ApiException(ApiError(ApiError.NETWORK, "offline"))
        }
        val vm = ComplaintViewModel("c1", "cp-homework", failing, Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        val before = settle(vm.state) { !it.loading }
        vm.dispatch(ComplaintContract.Intent.Reopen)
        val after = settle(vm.state) { it.reopenFailed }
        assertEquals(ChatThreadStatus.RESOLVED, after.complaint?.status)
        assertEquals(before.events, after.events)
        assertTrue(after.showReopen, "she can try again")
        assertFalse(after.reopening)
        assertTrue(Strings.en.complaints.reopenFailed.isNotBlank() && Strings.ar.complaints.reopenFailed.isNotBlank())
    }

    /** Review of #209: a complaint outside the current filter still moves both counts. */
    @Test fun theCountsFollowAComplaintTheFilterHides() = runBlocking {
        val socket = Socket()
        val shared = repo()
        val vm = ComplaintsViewModel(Kids(maya), shared, socket).also { built += it }
        vm.dispatch(ComplaintsContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(ComplaintsContract.Intent.SetFilter(ComplaintsContract.Filter.OPEN))
        val open = settle(vm.state) { !it.loading && it.filter == ComplaintsContract.Filter.OPEN }
        assertTrue(open.complaints.none { it.id == "cp-homework" })

        shared.reopen("c1", "cp-homework")                 // the staff side or another device reopened it
        socket.awaitCollector()
        socket.frames.emit(ChatFrame.Status("cp-homework", ChatThreadStatus.OPEN, 9L))
        val after = settle(vm.state) { it.open == 2 }
        assertEquals(0, after.resolved)
        assertTrue(after.complaints.any { it.id == "cp-homework" })
    }

    // ---- B5 photos and PDFs, through M7's pipeline

    /** Review of #209: a complaint photo goes up re-encoded — at most 2560 px — and without its EXIF or GPS. */
    @Test fun aComplaintPhotoGoesUpWithoutItsLocation() = runBlocking {
        val original = photoWithGps()
        assertTrue(original.contains(exifMarker) && original.contains(gpsTag), "the fixture must carry GPS to begin with")
        val vm = ComplaintViewModel("c1", "cp-bus", repo(), Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        settle(vm.state) { !it.loading }
        // What the complaint's picker hands over for a gallery photo (`AttachMenuButton`).
        vm.dispatch(ComplaintContract.Intent.AddFiles(listOf(PickedFile("IMG_2041.JPG", original.size.toLong(), photo = true) { photoToUpload("IMG_2041.JPG", original) })))
        settle(vm.state) { it.drafts.singleOrNull()?.ref != null }

        val sent = uploaded.single()
        assertEquals("image/jpeg", sent.mimeType)
        assertFalse(sent.bytes.contains(exifMarker), "no EXIF block may reach the server")
        assertFalse(sent.bytes.contains(gpsTag), "no GPS tag may reach the server")
        val image = Image.makeFromEncoded(sent.bytes)
        assertEquals(PHOTO_MAX_PX, maxOf(image.width, image.height))
    }

    @Test fun aReplyCanBeOnlyAPhoto_andWaitsForItsUpload() = runBlocking {
        val gate = CompletableDeferred<UploadFile?>()
        val vm = ComplaintViewModel("c1", "cp-bus", repo(), Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        val before = settle(vm.state) { !it.loading }.messages.size
        assertFalse(vm.state.value.canSend)
        launch { vm.dispatch(ComplaintContract.Intent.AddFiles(listOf(PickedFile("bus-stop.jpg", 2_000, photo = false) { gate.await() }))) }
        delay(50)
        assertFalse(vm.state.value.canSend, "the send waits for the upload")
        gate.complete(UploadFile("bus-stop.jpg", "image/jpeg", ByteArray(2_000)))
        assertTrue(settle(vm.state) { it.drafts.singleOrNull()?.ref != null }.canSend, "a photo alone is a message")

        vm.dispatch(ComplaintContract.Intent.Send)
        val sent = settle(vm.state) { s -> s.messages.size == before + 1 && s.messages.none { it.pending } }
        assertEquals(listOf("bus-stop.jpg"), sent.messages.last().message.attachments?.map { it.name })
        assertTrue(sent.drafts.isEmpty())
    }

    @Test fun aFileOverTheLimitsIsRefusedBeforeItIsRead() = runBlocking {
        val vm = ComplaintViewModel("c1", "cp-bus", repo(), Socket(), staging, uploader).also { built += it }
        vm.dispatch(ComplaintContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(ComplaintContract.Intent.AddFiles(listOf(PickedFile("huge.pdf", 20L * 1024 * 1024, photo = false) { error("must not be read") })))
        assertEquals(AttachmentRefusal.PDF_TOO_LARGE, settle(vm.state) { it.refusal != null }.refusal)
        assertTrue(uploaded.isEmpty())
    }

    @Test fun aNewComplaintCarriesItsFiles() = runBlocking {
        val vm = NewComplaintViewModel(Kids(maya), repo(), staging, uploader).also { built += it }
        vm.dispatch(NewComplaintContract.Intent.Load)
        settle(vm.state) { !it.loading }
        vm.dispatch(NewComplaintContract.Intent.SelectRecipient("co-lina"))
        vm.dispatch(NewComplaintContract.Intent.SetTitle("Marked wrongly"))
        vm.dispatch(NewComplaintContract.Intent.AddFiles(listOf(PickedFile("worksheet.pdf", 4_000, photo = false) { UploadFile("worksheet.pdf", "application/pdf", ByteArray(4_000)) })))
        assertTrue(settle(vm.state) { it.drafts.singleOrNull()?.ref != null }.canSend, "a PDF can stand in for the words")
        vm.dispatch(NewComplaintContract.Intent.Send)
        val created = withTimeout(5_000) { vm.effects.first() } as NewComplaintContract.Effect.Created
        assertEquals("Marked wrongly", created.complaint.title)
        assertEquals(listOf("worksheet.pdf"), created.complaint.lastMessage?.attachments?.map { it.name })
    }

    // ---- the small pieces

    @Test fun theTimelinePutsAnEventAfterAMessageAtTheSameInstant() {
        val msg = TimelineItem.Message(ChatMessage("m", "cp", ChatSender.TEACHER, "s", "Done.", 10L))
        val ev = ComplaintEvent(ChatThreadStatus.RESOLVED, ComplaintActor.STAFF, "s", "Ms. Lina", 10L)
        assertTrue(timeline(listOf(msg), listOf(ev)).last() is TimelineItem.Event)
    }

    @Test fun recipientsReadAsMessagesSpellsThem() {
        assertEquals("Subject coordinator · Math", recipientLabel(ChatPeerRole.COORDINATOR, "math", Strings.en))
        assertEquals("Department manager", recipientLabel(ChatPeerRole.MANAGERIAL, null, Strings.en))
        assertEquals("معلّمة · إنجليزي", recipientLabel(ChatPeerRole.TEACHER, "english", Strings.ar))
    }

    @Test fun theComplaintLinkIsRead() {
        assertEquals("c1" to "cp-1", complaintOf("/children/c1/complaints/cp-1"))
        assertNull(complaintOf("/children/c1/chat/t-1"))
        assertNull(complaintOf(null))
    }

    @Test fun aStatusChangeMovesTheBadges() {
        assertTrue(ParentBadges.movesBadges(ChatFrame.Status("cp-1", ChatThreadStatus.RESOLVED, 1L)))
    }

    @Test fun theOpenComplaintCarriesNoResolvedTrail() {
        val c = Complaint("cp", "c1", "Maya", "t", ChatThreadStatus.RESOLVED, "s", "Ms. Lina", ChatPeerRole.COORDINATOR, 1L, resolvedAt = 2L, resolvedByName = "Ms. Lina")
        val reopened = applyStatus(listOf(c), "cp", ChatThreadStatus.OPEN, 3L).single()
        assertNull(reopened.resolvedAt)
        assertNull(reopened.resolvedByName)
    }
}
