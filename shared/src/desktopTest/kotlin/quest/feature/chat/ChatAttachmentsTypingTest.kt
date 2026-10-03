package quest.feature.chat

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
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
import quest.api.ApiException
import quest.api.UploadFile
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatAttachment
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatTopic
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.MAX_PHOTO_BYTES
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.StagedUpload
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.domain.TYPING_TIMEOUT_MS
import quest.feature.chat.domain.asThumbnail
import quest.feature.chat.domain.contentTypeOf
import quest.feature.chat.domain.formatBytes
import quest.feature.chat.domain.preparePhoto
import quest.feature.chat.domain.refusalFor
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationViewModel
import quest.feature.chat.presentation.ChatThreadsViewModel
import quest.feature.chat.presentation.messagePreview
import quest.feature.chat.presentation.presenceLine
import quest.feature.chat.presentation.threadDescription
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.Strings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M7 — the owner's report of 2026-10-03: a photo or a PDF from the dashboard showed in the app as its name only, the
 * parent could not send one at all (the composer only wrote an `[attachment:…]` tag into the text), and "Manager is
 * typing" never appeared. These pin the file rules, the upload-then-send state machine and the typing indicator.
 */
class ChatAttachmentsTypingTest {

    private val thread = "th-nour"
    private val manager = "mg-nour"

    /** A repository whose uploads are driven by the test: each waits on its own gate, so progress can be observed. */
    private class FakeChat : ChatRepository {
        val frames = MutableSharedFlow<ChatFrame>(extraBufferCapacity = 32)
        override val connectionState = MutableStateFlow(ChatConnectionState.CONNECTED) as StateFlow<ChatConnectionState>
        override val incomingFrames: SharedFlow<ChatFrame> = frames

        var rows: List<ChatThread> = emptyList()
        var threadsAsked = 0
        val uploads = mutableListOf<StagedUpload>()
        val gates = mutableListOf<CompletableDeferred<Boolean>>()
        val sends = mutableListOf<Pair<String, List<String>>>()
        var uploadError: Throwable? = null
        var sendError: Throwable? = null

        override suspend fun threads(childId: String): List<ChatThread> { threadsAsked++; return rows }
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef {
            uploads += file
            uploadError?.let { throw it }
            val gate = CompletableDeferred<Boolean>().also { gates += it }
            onProgress(0.5f)
            if (!gate.await()) error("upload failed")
            return AttachmentRef("att-${uploads.size}", file.name, file.contentType, file.size, width = 800, height = 600)
        }
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage {
            sends += body to attachmentIds
            sendError?.let { throw it }
            return ChatMessage("m-${sends.size}", "th-nour", ChatSender.PARENT, "p1", body, 1L,
                attachments = attachmentIds.map { ChatAttachment(it, "image/jpeg", "photo.jpg", 3) })
        }
        override suspend fun markRead(childId: String, teacherId: String) {}
        override suspend fun sendTyping(childId: String, teacherId: String) {}
        override fun connect() {}
        override fun disconnect() {}

        suspend fun awaitCollectors(n: Int = 1) {
            repeat(400) { if (frames.subscriptionCount.value >= n) return; delay(5) }
            error("nobody subscribed to the frame stream")
        }
    }

    /** Staging in memory: what was staged, and which of it has been deleted again. */
    private class MemoryStaging : UploadStaging {
        val live = mutableMapOf<String, ByteArray>()
        var count = 0
        override suspend fun stage(file: UploadFile): StagedUpload {
            val path = "staged-${++count}"
            live[path] = file.bytes
            return StagedUpload(path, file.fileName, file.mimeType, file.bytes.size.toLong())
        }
        override fun discard(staged: StagedUpload) { live.remove(staged.path) }
    }

    private val staging = MemoryStaging()

    private val built = mutableListOf<androidx.lifecycle.ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun conversation(chat: FakeChat, threadId: String? = thread) =
        ChatConversationViewModel(
            ChatPeer(childId = "c1", staffId = manager, staffName = "Ms. Nour", staffRole = ChatStaffRole.MANAGERIAL, threadId = threadId),
            chat, staging,
        ).also { built += it }

    private suspend fun <S> StateFlow<S>.await(timeoutMs: Long = 2_000, predicate: (S) -> Boolean): S {
        repeat((timeoutMs / 5).toInt()) { if (predicate(value)) return value; delay(5) }
        error("state never satisfied the predicate; last was $value")
    }

    private fun photo(name: String = "photo.jpg", size: Int = 3) = PickedFile(name, size.toLong(), photo = true) { UploadFile(name, "image/jpeg", ByteArray(size)) }
    private fun document(name: String, size: Long) = PickedFile(name, size, photo = false) { UploadFile(name, "application/pdf", ByteArray(3)) }

    // ---- the rules, before a byte is read

    @Test fun typeAndSizeAreCheckedBeforeAnythingIsRead() {
        assertNull(refusalFor("plan.pdf", 10L * 1024 * 1024, already = 0))
        assertEquals(AttachmentRefusal.PDF_TOO_LARGE, refusalFor("plan.pdf", 10L * 1024 * 1024 + 1, already = 0))
        assertEquals(AttachmentRefusal.PHOTO_TOO_LARGE, refusalFor("scan.png", MAX_PHOTO_BYTES + 1, already = 0))
        assertEquals(AttachmentRefusal.WRONG_TYPE, refusalFor("notes.docx", 10, already = 0))
        assertEquals(AttachmentRefusal.TOO_MANY, refusalFor("a.jpg", 10, already = 5))
        // A gallery or camera photo is re-encoded rather than refused — an iPhone's HEIC included.
        assertNull(refusalFor("IMG_0001.HEIC", 9_000_000, already = 0, photo = true))
        assertEquals("image/webp", contentTypeOf("A.WEBP"))
    }

    @Test fun everyPhotoIsReEncodedAsAJpeg() {
        val png = preparePhoto("a.png", ByteArray(10)) { byteArrayOf(1, 2, 3) }!!
        assertEquals("a.jpg" to "image/jpeg", png.fileName to png.mimeType)
        val heic = preparePhoto("IMG_0001.HEIC", ByteArray(10)) { byteArrayOf(1, 2, 3) }!!
        assertEquals("IMG_0001.jpg" to "image/jpeg", heic.fileName to heic.mimeType)
        assertNull(preparePhoto("broken.heic", ByteArray(10)) { null })
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("820 KB", formatBytes(820 * 1024))
    }

    @Test fun aThumbnailIsCachedApartFromTheFullSizeFile() {
        val file = ChatAttachment("a1", "image/jpeg", "p.jpg", 10)
        assertTrue(file.asThumbnail().url.endsWith("/media/attachments/a1?w=640"))
        assertTrue(file.asThumbnail().id != file.id, "the thumbnail must never stand in for the full-size bytes")
    }

    @Test fun previewsNameFilesByKindAndLegacyTagsStayText() {
        fun msg(body: String, vararg files: ChatAttachment) = ChatMessage("m", "t", ChatSender.TEACHER, "s", body, 1, attachments = files.toList())
        val photo = ChatAttachment("a", "image/jpeg", "p.jpg", 1)
        val pdf = ChatAttachment("b", "application/pdf", "plan.pdf", 1)
        assertEquals("📷 Photo", messagePreview(msg("", photo), Strings.en))
        assertEquals("📷 2 photos", messagePreview(msg("", photo, photo), Strings.en))
        assertEquals("📄 plan.pdf · This week", messagePreview(msg("This week", pdf), Strings.en))
        assertEquals("📷 صورة", messagePreview(msg("", photo), Strings.ar))
        assertEquals("[attachment:a1:image:report.png:1 KB]", messagePreview(msg("[attachment:a1:image:report.png:1 KB]"), Strings.en))
    }

    // ---- upload, then send

    @Test fun aPhotoUploadsAsSoonAsItIsPickedAndTheMessageCarriesItsId() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        val uploading = vm.state.await { it.drafts.singleOrNull()?.progress == 0.5f }
        assertTrue(uploading.drafts.single().uploading)
        assertFalse(uploading.canSend, "nothing goes until every file is up")

        chat.gates.single().complete(true)
        vm.state.await { it.canSend }
        vm.dispatch(ChatConversationContract.Intent.UpdateInput("This is the homework"))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        val sent = vm.state.await { s -> s.messages.singleOrNull()?.isPending == false }

        assertEquals("This is the homework" to listOf("att-1"), chat.sends.single())
        assertTrue(staging.live.isEmpty(), "the staged copy goes as soon as the server has the file")
        assertTrue(sent.drafts.isEmpty())
        assertEquals(listOf("att-1"), sent.messages.single().attachments.map { it.id })
    }

    @Test fun filesAloneAreAMessage() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        vm.state.await { chat.gates.isNotEmpty() }
        chat.gates.single().complete(true)
        vm.state.await { it.canSend }
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        vm.state.await { it.messages.isNotEmpty() }
        assertEquals("", chat.sends.single().first)
    }

    @Test fun aFailedUploadStaysWithARetryAndBlocksTheSend() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        vm.state.await { chat.gates.isNotEmpty() }
        chat.gates.single().complete(false)
        val failed = vm.state.await { it.drafts.single().failed }
        assertFalse(failed.copy(inputText = "hello").canSend, "a message never leaves without the file she attached")

        vm.dispatch(ChatConversationContract.Intent.RetryDraft(failed.drafts.single().localId))
        vm.state.await { chat.gates.size == 2 && !it.drafts.single().failed }
        chat.gates[1].complete(true)
        vm.state.await { it.drafts.single().ref != null }
        assertEquals(2, chat.uploads.size, "the retry sends the same bytes again")
    }

    @Test fun refusedFilesAreNamedAndNeverUploaded() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(document("big.pdf", 11L * 1024 * 1024))))
        vm.state.await { it.refusal == AttachmentRefusal.PDF_TOO_LARGE }
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(PickedFile("x.docx", 10, photo = false) { null })))
        vm.state.await { it.refusal == AttachmentRefusal.WRONG_TYPE }
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(PickedFile("gone.jpg", 10, photo = true) { null })))
        vm.state.await { it.refusal == AttachmentRefusal.UNREADABLE }
        assertTrue(chat.uploads.isEmpty())

        // Five fit; the sixth is refused with its own sentence, and the five stay.
        vm.dispatch(ChatConversationContract.Intent.AddFiles((1..6).map { photo("p$it.jpg") }))
        val full = vm.state.await { it.drafts.size == 5 && it.refusal == AttachmentRefusal.TOO_MANY }
        assertEquals(5, full.drafts.size)
        assertTrue(Strings.ar.chatFiles.attachTooMany.isNotBlank() && Strings.en.chatFiles.attachTooMany.contains("5"))

        vm.dispatch(ChatConversationContract.Intent.RemoveDraft(full.drafts.first().localId))
        vm.state.await { it.drafts.size == 4 && it.refusal == null }
        assertEquals(4, staging.live.size, "a removed file's staged copy is deleted with it")
        assertEquals(AttachmentRefusal.PHOTO_TOO_LARGE, refusalFor("huge.heic", 26L * 1024 * 1024, already = 0, photo = true))
    }

    @Test fun theServersRefusalsAreNamedAndOnlyARetryableFailureStays() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        chat.uploadError = ApiException(ApiError("image_too_large", "An image may be at most 8192 pixels on a side"))
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        vm.state.await { it.refusal == AttachmentRefusal.PHOTO_TOO_MANY_PIXELS && it.drafts.isEmpty() }
        chat.uploadError = ApiException(ApiError("too_large", "A PDF must be under 10 MB"))
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(document("plan.pdf", 3))))
        vm.state.await { it.refusal == AttachmentRefusal.PDF_TOO_LARGE && it.drafts.isEmpty() }
        chat.uploadError = ApiException(ApiError(ApiError.NETWORK, "offline"))
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        vm.state.await { it.drafts.singleOrNull()?.failed == true }
        assertTrue(Strings.ar.chatFiles.attachTooManyPixels.isNotBlank())
    }

    @Test fun aFileAlreadySentIsExplained() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        vm.dispatch(ChatConversationContract.Intent.AddFiles(listOf(photo())))
        vm.state.await { chat.gates.isNotEmpty() }
        chat.gates.single().complete(true)
        vm.state.await { it.canSend }
        chat.sendError = ApiException(ApiError("attachment_already_sent", "That file was already sent"))
        vm.dispatch(ChatConversationContract.Intent.SendMessage)
        val failed = vm.state.await { it.messages.singleOrNull()?.isFailed == true }
        assertEquals(AttachmentRefusal.ALREADY_SENT, failed.refusal)
    }

    // ---- typing

    @Test fun herTypingShowsByNameAndLapses() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing(thread, ChatSender.TEACHER))
        val typing = vm.state.await { it.isTeacherTyping }
        assertEquals("Manager is typing…" to true, presenceLine(typing, Strings.en))
        vm.state.await(TYPING_TIMEOUT_MS + 2_000) { !it.isTeacherTyping }
    }

    @Test fun herMessageEndsTheTypingAtOnce() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing(thread, ChatSender.TEACHER))
        vm.state.await { it.isTeacherTyping }
        chat.frames.emit(ChatFrame.Message(ChatMessage("m9", thread, ChatSender.TEACHER, manager, "Here it is", 2L)))
        vm.state.await(1_000) { !it.isTeacherTyping && it.messages.size == 1 }
    }

    @Test fun theParentsOwnTypingEchoIsNotShown() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat)
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing(thread, ChatSender.PARENT))
        // Frames are handled in order: once the presence frame behind it has landed, the typing frame was seen.
        chat.frames.emit(ChatFrame.Presence(online = true, userId = manager))
        vm.state.await { it.peerOnline == true }
        assertFalse(vm.state.value.isTeacherTyping)
    }

    /**
     * The app-side defect behind "Manager is typing never shows": a conversation opened before its thread existed
     * (a row with `id: null`, no history) held a null thread id, and every `typing` frame — which names only its
     * thread, and says `teacher` for any staff member — looked like another thread's. Each peer type now finds its
     * thread on open and says who is typing by role.
     */
    @Test fun everyStaffPeerWithNoHistoryShowsTypingByRole() = runBlocking<Unit> {
        data class Case(val role: ChatStaffRole, val peerRole: ChatPeerRole?, val admin: Boolean, val label: String, val labelAr: String)
        val cases = listOf(
            Case(ChatStaffRole.TEACHER, ChatPeerRole.TEACHER, false, "Teacher is typing…", Strings.ar.chatFiles.typingTeacher),
            Case(ChatStaffRole.COORDINATOR, ChatPeerRole.COORDINATOR, false, "Coordinator is typing…", Strings.ar.chatFiles.typingCoordinator),
            Case(ChatStaffRole.MANAGERIAL, ChatPeerRole.MANAGERIAL, false, "Manager is typing…", Strings.ar.chatFiles.typingManager),
            Case(ChatStaffRole.MANAGERIAL, null, true, "School administration is typing…", Strings.ar.chatFiles.typingAdmin),
        )
        for (case in cases) {
            val staff = "staff-${case.role}-${case.admin}"
            val id = "th-$staff"
            val chat = FakeChat()
            chat.rows = listOf(ChatThread(id = id, childId = "c1", childName = "Hala", teacherId = staff, teacherName = "X",
                staffRole = case.role, peerRole = case.peerRole, withAdmin = if (case.admin) true else null))
            val vm = ChatConversationViewModel(
                ChatPeer("c1", staff, "X", staffRole = case.role, threadId = null, peerRole = case.peerRole, withAdmin = case.admin), chat, staging,
            ).also { built += it }
            vm.dispatch(ChatConversationContract.Intent.Load)
            vm.state.await { it.threadId == id }
            chat.awaitCollectors()
            chat.frames.emit(ChatFrame.Typing(id, ChatSender.TEACHER))
            val typing = vm.state.await { it.isTeacherTyping }
            assertEquals(case.label to true, presenceLine(typing, Strings.en), "${case.role} admin=${case.admin}")
            assertEquals(case.labelAr, presenceLine(typing, Strings.ar)!!.first)
        }
    }

    /** The staff side opens the thread while the conversation is already on screen: the first frame finds it. */
    @Test fun aThreadOpenedAfterTheConversationIsFoundByItsFirstTypingFrame() = runBlocking<Unit> {
        val chat = FakeChat()
        val vm = conversation(chat, threadId = null)
        vm.dispatch(ChatConversationContract.Intent.Load)
        vm.state.await { !it.loading }
        assertNull(vm.state.value.threadId)
        chat.rows = listOf(ChatThread(id = thread, childId = "c1", childName = "Hala", teacherId = manager, teacherName = "Ms. Nour", staffRole = ChatStaffRole.MANAGERIAL))
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing(thread, ChatSender.TEACHER))
        val now = vm.state.await { it.isTeacherTyping }
        assertEquals(thread, now.threadId)
    }

    @Test fun anotherStaffMembersThreadIsAskedAboutOnce() = runBlocking<Unit> {
        val chat = FakeChat()
        chat.rows = listOf(ChatThread(id = null, childId = "c1", childName = "Hala", teacherId = manager, teacherName = "Ms. Nour"))
        val vm = conversation(chat, threadId = null)
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing("th-math", ChatSender.TEACHER))
        chat.frames.emit(ChatFrame.Typing("th-math", ChatSender.TEACHER))
        // Frames are handled in order: once the presence frame behind them has landed, both were.
        chat.frames.emit(ChatFrame.Presence(online = true, userId = manager))
        vm.state.await { it.peerOnline == true }
        assertFalse(vm.state.value.isTeacherTyping)
        assertEquals(1, chat.threadsAsked)
    }

    @Test fun theThreadListShowsWhoIsTyping() = runBlocking<Unit> {
        val chat = FakeChat()
        val children = object : ChildrenRepository {
            override val currentChild: StateFlow<Child?> = MutableStateFlow(Child("c1", "Hala", "sun", Curriculum.BRITISH, 1))
            override suspend fun refresh(): List<Child> = emptyList()
            override suspend fun children(): List<Child> = emptyList()
            override suspend fun select(id: String) {}
            override suspend fun clear() {}
        }
        val vm = ChatThreadsViewModel(children, chat).also { built += it }
        chat.awaitCollectors()
        chat.frames.emit(ChatFrame.Typing(thread, ChatSender.TEACHER))
        vm.state.await { thread in it.typing }
        val row = ChatThread(id = thread, childId = "c1", childName = "Hala", teacherId = manager, teacherName = "Ms. Nour", staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.MANAGERIAL)
        assertTrue(threadDescription(row, Strings.en, typing = true).contains("Manager is typing…"))
        chat.frames.emit(ChatFrame.Message(ChatMessage("m9", thread, ChatSender.TEACHER, manager, "Done", 2L)))
        vm.state.await { thread !in it.typing }
    }
}
