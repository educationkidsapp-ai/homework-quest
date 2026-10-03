package quest.feature.chat.presentation

import quest.core.text.isolate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.Resolver
import quest.feature.chat.domain.resolverOf
import quest.feature.chat.domain.ChatRepository
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.featureEnabled
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
import io.github.vinceglb.filekit.core.PlatformFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import quest.api.UploadFile
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatAttachment
import quest.core.platform.photoAsJpeg
import quest.core.platform.rememberCameraCapture
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.MAX_ATTACHMENTS
import quest.feature.chat.domain.PHOTO_MAX_PX
import quest.feature.chat.domain.PHOTO_QUALITY
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.TYPING_TIMEOUT_MS
import quest.feature.chat.domain.asChatAttachment
import quest.feature.chat.domain.contentTypeOf
import quest.feature.chat.domain.preparePhoto
import quest.feature.chat.domain.refusalFor
import quest.feature.chat.domain.refusalForUpload
import quest.api.ApiException
import quest.ui.design.AnimatedLoadingView
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.ParentTheme

object ChatConversationContract {
    data class UiMessage(
        val id: String,
        val body: String,
        val isFromParent: Boolean,
        val createdAt: Long,
        val readAt: Long? = null,
        val isPending: Boolean = false,
        val isFailed: Boolean = false,
        val clientId: String? = null,
        /**
         * The `topic` this send carried, kept so a retry resends it. The toggle goes off as the message leaves, so
         * without this one transient 5xx would quietly downgrade a parent's complaint to a question.
         */
        val topic: ChatTopic? = null,
        /** M7 (B5): the files on the message, in the order they were sent; a retry sends the same ids again. */
        val attachments: List<ChatAttachment> = emptyList(),
    )

    /**
     * M7: one file in the composer's tray. It uploads the moment it is picked, so by the time she has written her
     * sentence the photo is usually up; [ref] is the server's answer, and the message can only go once every draft
     * has one. A failed upload stays in the tray with its own retry rather than disappearing.
     */
    data class Draft(
        val localId: String,
        val name: String,
        val contentType: String,
        val size: Long,
        val progress: Float = 0f,
        val ref: AttachmentRef? = null,
        val failed: Boolean = false,
    ) {
        val uploading: Boolean get() = ref == null && !failed
    }

    data class State(
        val childId: String = "",
        val teacherId: String = "",
        val teacherName: String = "",
        val loading: Boolean = true,
        val isTeacherTyping: Boolean = false,
        val connectionState: ChatConnectionState = ChatConnectionState.DISCONNECTED,
        val inputText: String = "",
        val messages: List<UiMessage> = emptyList(),
        val errorMessage: String? = null,
        // R8 (DR3): who holds the other side, what the thread is about, and where it stands.
        val staffRole: ChatStaffRole = ChatStaffRole.TEACHER,
        val subject: String? = null,
        val topic: ChatTopic = ChatTopic.QUESTION,
        val resolved: Boolean = false,
        val markAsComplaint: Boolean = false,
        /** S1: the school administration holds the other side. She can answer; a complaint is not offered here. */
        val withAdmin: Boolean = false,
        /**
         * The thread this conversation is, once one exists. `/ws/chat` is **one socket per parent** and fans out every
         * thread of hers, so a `status` frame has to be matched against this before it moves anything — otherwise a
         * coordinator resolving the Math complaint flips the banner inside an open English conversation.
         */
        val threadId: String? = null,
        /**
         * M4 (D6): whether the person on the other end is online — T1's `peerOnline` from the row, then the `presence`
         * frames about her. Null is unknown, and the header then says nothing; this app's own socket being connected
         * says nothing about her.
         */
        val peerOnline: Boolean? = null,
        /** M4 (D7): who resolves this thread, and so who the Resolved banner says answered. */
        val resolver: Resolver = Resolver.COORDINATOR,
        /** M7: the files waiting to go with the next message. */
        val drafts: List<Draft> = emptyList(),
        /** M7: why the last file she picked was not added — one sentence under the tray until she picks again. */
        val refusal: AttachmentRefusal? = null,
    ) : MviState {
        /**
         * The toggle is offered on any thread that is not a complaint already — to a teacher, a coordinator or a
         * manager alike, new thread or old (M1): the server accepts `topic: complaint` on any message and turns the
         * thread into an open complaint from there.
         *
         * `!loading` keeps it from flashing over an existing complaint in the moment before its history lands.
         */
        val canMarkComplaint: Boolean
            get() = !loading && !withAdmin && topic == ChatTopic.QUESTION

        /** Words, files or both — and only once every file is up, so nothing is sent without what she attached. */
        val canSend: Boolean
            get() = drafts.all { it.ref != null } && inputText.length <= 2000 && (inputText.isNotBlank() || drafts.isNotEmpty())
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class UpdateInput(val text: String) : Intent
        data object SendMessage : Intent
        data class RetrySend(val clientId: String) : Intent
        data object ToggleComplaint : Intent
        /** M7: files from the camera, the gallery or the document picker, checked and uploaded one by one. */
        data class AddFiles(val files: List<PickedFile>) : Intent
        data class RemoveDraft(val localId: String) : Intent
        data class RetryDraft(val localId: String) : Intent
    }

    sealed interface Effect : MviEffect
}

class ChatConversationViewModel(
    private val peer: ChatPeer,
    private val chat: ChatRepository,
) : MviViewModel<ChatConversationContract.State, ChatConversationContract.Intent, ChatConversationContract.Effect>(
    ChatConversationContract.State(
        childId = peer.childId, teacherId = peer.staffId, teacherName = peer.staffName,
        staffRole = peer.staffRole, subject = peer.subject, topic = peer.topic, resolved = peer.resolved,
        // "Complaint" chosen in New message arrives as the toggle already on: nothing is a complaint until the server
        // has taken a message that says so.
        markAsComplaint = peer.startAsComplaint && !peer.withAdmin && peer.topic == ChatTopic.QUESTION,
        withAdmin = peer.withAdmin,
        threadId = peer.threadId,
        peerOnline = peer.peerOnline,
        resolver = resolverOf(peer.peerRole, peer.staffRole, peer.withAdmin),
    )
) {
    private val childId get() = peer.childId
    private val teacherId get() = peer.staffId

    private var typingJob: Job? = null
    private var lastTypingSentMillis: Long = 0L

    /** M7: the bytes behind each draft, kept for its retry and dropped once the message is sent or the draft removed. */
    private val pendingFiles = mutableMapOf<String, UploadFile>()
    private val uploadJobs = mutableMapOf<String, Job>()

    /** M7: thread ids a `typing` frame named that turned out not to be this conversation — asked about once each. */
    private val otherThreads = mutableSetOf<String>()

    override suspend fun handle(intent: ChatConversationContract.Intent) {
        when (intent) {
            ChatConversationContract.Intent.Load -> loadInitialMessages()
            is ChatConversationContract.Intent.UpdateInput -> handleInputChanged(intent.text)
            ChatConversationContract.Intent.SendMessage -> sendMessage()
            is ChatConversationContract.Intent.RetrySend -> retrySend(intent.clientId)
            ChatConversationContract.Intent.ToggleComplaint -> reduce { copy(markAsComplaint = !markAsComplaint) }
            is ChatConversationContract.Intent.AddFiles -> addFiles(intent.files)
            is ChatConversationContract.Intent.RemoveDraft -> removeDraft(intent.localId)
            is ChatConversationContract.Intent.RetryDraft -> startUpload(intent.localId)
        }
    }

    private suspend fun loadInitialMessages() {
        chat.connect()
        try {
            val history = chat.messages(childId, teacherId)
            val uiList = history.map { it.toUiMessage() }
            reduce { copy(loading = false, messages = uiList, threadId = history.firstOrNull()?.threadId ?: threadId) }
            // M7: a thread with no history yet — one the staff side has just opened — still has an id the socket
            // names it by, so it is looked up rather than left null until somebody writes.
            if (current.threadId == null) resolveThreadId()
            // M4 (D11): a thread nobody has written in yet does not exist on the server, and `…/read` on it is a 404.
            if (current.threadId != null) chat.markRead(childId, teacherId)
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message) }
        }
    }

    private suspend fun handleInputChanged(text: String) {
        reduce { copy(inputText = text) }
        val now = Today.epochMillis()
        if (text.isNotBlank() && now - lastTypingSentMillis > 3_500L) {
            lastTypingSentMillis = now
            chat.sendTyping(childId, teacherId)
        }
    }

    /**
     * Each file is checked before a byte of it is read (type, size, room on the message), then read off the main
     * thread — a photo the server would refuse as it is is re-encoded there — and checked again as it will go up.
     * The last refusal is the one sentence the tray shows; the files that passed are added either way.
     */
    private suspend fun addFiles(files: List<PickedFile>) {
        var refusal: AttachmentRefusal? = null
        for (file in files) {
            val early = refusalFor(file.name, file.size, current.drafts.size, file.photo)
            if (early != null) { refusal = early; continue }
            val upload = withContext(Dispatchers.Default) { runCatching { file.read() }.getOrNull() }
            if (upload == null || upload.bytes.isEmpty()) { refusal = AttachmentRefusal.UNREADABLE; continue }
            val late = refusalFor(upload.fileName, upload.bytes.size.toLong(), current.drafts.size)
            if (late != null) { refusal = late; continue }
            val localId = Ids.random()
            pendingFiles[localId] = upload
            reduce {
                copy(drafts = drafts + ChatConversationContract.Draft(localId, upload.fileName, upload.mimeType, upload.bytes.size.toLong()))
            }
            startUpload(localId)
        }
        reduce { copy(refusal = refusal) }
    }

    private fun startUpload(localId: String) {
        val file = pendingFiles[localId] ?: return
        updateDraft(localId) { it.copy(progress = 0f, failed = false) }
        uploadJobs[localId]?.cancel()
        uploadJobs[localId] = launch {
            try {
                val ref = chat.uploadAttachment(childId, file) { sent -> updateDraft(localId) { it.copy(progress = sent) } }
                updateDraft(localId) { it.copy(ref = ref, progress = 1f) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // A refusal no retry can cure takes the file out of the tray and says why; anything else stays to retry.
                val refusal = refusalForUpload(e.error.code, file.mimeType)
                if (refusal == null) updateDraft(localId) { it.copy(failed = true) }
                else { dropDraft(localId); reduce { copy(refusal = refusal) } }
            } catch (_: Throwable) {
                updateDraft(localId) { it.copy(failed = true) }
            }
        }
    }

    private fun updateDraft(localId: String, change: (ChatConversationContract.Draft) -> ChatConversationContract.Draft) =
        reduce { copy(drafts = drafts.map { if (it.localId == localId) change(it) else it }) }

    private fun removeDraft(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        dropDraft(localId)
        reduce { copy(refusal = null) }
    }

    private fun dropDraft(localId: String) {
        pendingFiles.remove(localId)
        uploadJobs.remove(localId)
        reduce { copy(drafts = drafts.filterNot { it.localId == localId }) }
    }

    private suspend fun sendMessage() {
        val state = current
        if (!state.canSend) return
        val body = state.inputText.trim()
        val attachments = state.drafts.mapNotNull { it.ref?.asChatAttachment() }

        // The topic rides on exactly one send — the one made while the toggle is on. The toggle goes off with it, so a
        // failed send does not mark the next message as well; its retry carries the topic the message kept.
        val opening = state.canMarkComplaint && state.markAsComplaint
        val sentTopic = if (opening) ChatTopic.COMPLAINT else null

        val clientId = Ids.random()
        val pendingMsg = ChatConversationContract.UiMessage(
            id = clientId,
            body = body,
            isFromParent = true,
            createdAt = Today.epochMillis(),
            isPending = true,
            clientId = clientId,
            topic = sentTopic,
            attachments = attachments,
        )

        state.drafts.forEach { pendingFiles.remove(it.localId); uploadJobs.remove(it.localId) }
        reduce { copy(inputText = "", markAsComplaint = false, drafts = emptyList(), refusal = null, messages = messages + pendingMsg) }
        deliver(pendingMsg)
    }

    private suspend fun retrySend(clientId: String) {
        val target = current.messages.firstOrNull { it.clientId == clientId } ?: return
        reduce {
            val updated = messages.map { msg ->
                if (msg.clientId == clientId) msg.copy(isPending = true, isFailed = false) else msg
            }
            copy(messages = updated)
        }
        deliver(target)
    }

    /**
     * Sends [message] — its words, its files' ids and the `topic` it was written with. The original `topic` goes out
     * again on a retry: dropping it there would file the parent's complaint as an ordinary question.
     */
    private suspend fun deliver(message: ChatConversationContract.UiMessage) {
        val clientId = message.clientId ?: return
        try {
            val confirmed = chat.sendMessage(childId, teacherId, message.body, clientId, message.topic, message.attachments.map { it.id })
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                // A thread that has just become a complaint is an open one, whatever it was before.
                copy(
                    messages = updated,
                    topic = message.topic ?: topic,
                    resolved = if (message.topic == ChatTopic.COMPLAINT) false else resolved,
                    threadId = confirmed.threadId,
                )
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // B5: `409 attachment_already_sent` — a retry would only be refused again, so the tray says what to do.
            val alreadySent = (e as? ApiException)?.error?.code == "attachment_already_sent"
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) msg.copy(isPending = false, isFailed = true) else msg
                }
                copy(messages = updated, refusal = if (alreadySent) AttachmentRefusal.ALREADY_SENT else refusal)
            }
        }
    }

    init {
        // Collect connection status
        launch {
            chat.connectionState.collect { conn ->
                val prev = current.connectionState
                reduce { copy(connectionState = conn) }
                if (prev != ChatConnectionState.CONNECTED && conn == ChatConnectionState.CONNECTED) {
                    // Refetch messages since last received to fill any disconnect gap
                    val lastId = current.messages.lastOrNull { !it.isPending }?.id
                    if (lastId != null) {
                        runCatching {
                            val fresh = chat.messages(childId, teacherId, since = lastId)
                            if (fresh.isNotEmpty()) {
                                val freshUi = fresh.map { it.toUiMessage() }
                                reduce { copy(messages = messages + freshUi) }
                                chat.markRead(childId, teacherId)
                            }
                        }
                    }
                }
            }
        }

        // Collect incoming socket frames
        launch {
            chat.incomingFrames.collect { frame ->
                when (frame) {
                    is ChatFrame.Message -> {
                        val msg = frame.message
                        val senderTeacher = msg.sender == ChatSender.TEACHER && msg.senderId == teacherId
                        val clientAck = frame.clientId != null && current.messages.any { it.clientId == frame.clientId }

                        if (senderTeacher || clientAck) {
                            reduce { copy(threadId = msg.threadId) }
                            reduce {
                                if (frame.clientId != null) {
                                    val updated = messages.map {
                                        if (it.clientId == frame.clientId) msg.toUiMessage(isPending = false) else it
                                    }
                                    copy(messages = updated)
                                } else {
                                    val exists = messages.any { it.id == msg.id }
                                    if (!exists) copy(messages = messages + msg.toUiMessage()) else this
                                }
                            }
                            if (senderTeacher) {
                                // M7: her message is what the dots were announcing — they stop with it, not 5 s later.
                                typingJob?.cancel()
                                reduce { copy(isTeacherTyping = false) }
                                chat.markRead(childId, teacherId)
                            }
                        }
                    }
                    is ChatFrame.Read -> {
                        if (frame.readBy == ChatSender.TEACHER && isOurs(frame.threadId)) {
                            reduce {
                                val updated = messages.map { m ->
                                    if (m.isFromParent && m.readAt == null && m.createdAt <= frame.readAt) {
                                        m.copy(readAt = frame.readAt)
                                    } else m
                                }
                                copy(messages = updated)
                            }
                        }
                    }
                    is ChatFrame.Typing -> {
                        if (frame.from == ChatSender.TEACHER && isOursOrResolve(frame.threadId)) {
                            reduce { copy(isTeacherTyping = true) }
                            typingJob?.cancel()
                            typingJob = launch {
                                delay(TYPING_TIMEOUT_MS)
                                reduce { copy(isTeacherTyping = false) }
                            }
                        }
                    }
                    // R4's additive frame: the coordinator resolved (or re-opened) the thread. The banner follows it —
                    // but only when the frame is about *this* thread. See [isOurs].
                    is ChatFrame.Status -> {
                        if (isOurs(frame.threadId)) {
                            reduce { copy(resolved = frame.status == ChatThreadStatus.RESOLVED) }
                        }
                    }
                    // T1: the staff member on this thread came online or went offline. A frame about anybody else —
                    // the socket carries every thread of the parent's — leaves the header alone.
                    is ChatFrame.Presence -> {
                        if (frame.userId != null && frame.userId == teacherId) reduce { copy(peerOnline = frame.online) }
                    }
                    is ChatFrame.Error -> {
                        if (frame.clientId != null) {
                            reduce {
                                val updated = messages.map { m ->
                                    if (m.clientId == frame.clientId) m.copy(isPending = false, isFailed = true) else m
                                }
                                copy(messages = updated)
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onCleared() {
        uploadJobs.values.forEach { it.cancel() }
        super.onCleared()
    }

    /**
     * Whether a per-thread frame belongs to the conversation on screen.
     *
     * `/ws/chat` is one socket per parent and fans out **every** thread of hers, so `status`, `read` and `typing` all
     * arrive here for conversations this screen is not showing. Without this, a coordinator resolving the Math
     * complaint flips the Resolved banner inside an open English thread (and the same for read ticks and the typing
     * dot). A null [State.threadId] means no thread exists yet, so nothing that names one can be about us.
     */
    private fun isOurs(frameThreadId: String): Boolean = current.threadId == frameThreadId

    /**
     * M7: [isOurs] for a `typing` frame, which names its thread and nothing else. A conversation whose thread did not
     * exist when it opened keeps a null id until a message arrives, and every frame then looked like another
     * thread's — so a manager who opened the thread from the dashboard typed into silence. The first frame about an
     * unknown thread looks this conversation's thread up again; a thread that turns out to be somebody else's is
     * remembered, so it costs one request, not one per keystroke.
     */
    private suspend fun isOursOrResolve(frameThreadId: String): Boolean {
        if (isOurs(frameThreadId)) return true
        if (current.threadId != null || frameThreadId in otherThreads) return false
        resolveThreadId()
        if (!isOurs(frameThreadId)) otherThreads += frameThreadId
        return isOurs(frameThreadId)
    }

    /**
     * This conversation's thread id from the parent's own rows: her thread list (teachers, and every coordinator,
     * manager and administration thread that exists), then the coordinator and manager choosers, whose rows carry
     * the id too once a thread exists. The row is the one whose staff member is this conversation's.
     */
    private suspend fun resolveThreadId() {
        val lists: List<suspend () -> List<ChatThread>> = listOf({ chat.threads(childId) }, { chat.coordinators(childId) }, { chat.managers(childId) })
        for (list in lists) {
            val id = runCatching { list() }.getOrNull()?.firstOrNull { it.teacherId == teacherId && it.id != null }?.id
            if (id != null) { reduce { copy(threadId = id) }; return }
        }
    }

    private fun ChatMessage.toUiMessage(isPending: Boolean = false): ChatConversationContract.UiMessage =
        ChatConversationContract.UiMessage(
            id = id,
            body = body,
            isFromParent = sender == ChatSender.PARENT,
            createdAt = createdAt,
            readAt = readAt,
            isPending = isPending,
            // B5: absent on a message with no files.
            attachments = attachments.orEmpty(),
        )
}

@Composable
fun ChatConversationRoute(
    peer: ChatPeer,
    onBack: () -> Unit,
) {
    val vm: ChatConversationViewModel = koinViewModel(key = peer.staffId) { parametersOf(peer) }
    val state by vm.state.collectAsStateWithLifecycle()
    val parent: ParentRepository = koinInject()
    val language by parent.language.collectAsStateWithLifecycle()
    val arabic = featureEnabled(Flags.PARENT_PANEL_ARABIC)
    val strings = if (arabic) Strings.forLanguage(language) else Strings.en

    LaunchedEffect(vm) {
        vm.dispatch(ChatConversationContract.Intent.Load)
    }

    FeatureGate(Flags.CHAT) {
        ParentTheme(rtl = strings.isRtl) {
            CompositionLocalProvider(LocalStrings provides strings) {
                ChatConversationScreen(
                    state = state,
                    strings = strings,
                    onBack = onBack,
                    onInputChange = { vm.dispatch(ChatConversationContract.Intent.UpdateInput(it)) },
                    onSend = { vm.dispatch(ChatConversationContract.Intent.SendMessage) },
                    onRetry = { vm.dispatch(ChatConversationContract.Intent.RetrySend(it)) },
                    onToggleComplaint = { vm.dispatch(ChatConversationContract.Intent.ToggleComplaint) },
                    onPick = { vm.dispatch(ChatConversationContract.Intent.AddFiles(it)) },
                    onRemoveDraft = { vm.dispatch(ChatConversationContract.Intent.RemoveDraft(it)) },
                    onRetryDraft = { vm.dispatch(ChatConversationContract.Intent.RetryDraft(it)) },
                )
            }
        }
    }
}

/**
 * The header's presence line and whether its dot is lit; null when nothing is known about the other person. M7: the
 * typing line says who by role — "Manager is typing…" — because the frame itself only ever says `teacher` for staff.
 */
fun presenceLine(state: ChatConversationContract.State, strings: Strings): Pair<String, Boolean>? = when {
    state.isTeacherTyping -> typingLine(state.resolver, strings) to true
    state.peerOnline == true -> strings.online to true
    state.peerOnline == false -> strings.offline to false
    else -> null
}

/** M7: who is typing, from the thread's `peerRole` (falling back to its staff side) as [Resolver] reads it. */
fun typingLine(who: Resolver, strings: Strings): String = when (who) {
    Resolver.TEACHER -> strings.chatFiles.typingTeacher
    Resolver.COORDINATOR -> strings.chatFiles.typingCoordinator
    Resolver.MANAGER -> strings.chatFiles.typingManager
    Resolver.ADMIN -> strings.chatFiles.typingAdmin
}

/** M4 (D7): "Resolved — the … answered this", naming whoever the parent was writing to. */
fun resolvedBanner(resolver: Resolver, strings: Strings): String = when (resolver) {
    Resolver.TEACHER -> strings.resolvedBannerTeacher
    Resolver.COORDINATOR -> strings.resolvedBanner
    Resolver.MANAGER -> strings.resolvedBannerManager
    Resolver.ADMIN -> strings.resolvedBannerAdmin
}

/** A file from FileKit's picker as the view model takes it: sized up front, read (and a photo prepared) on demand. */
private fun PlatformFile.picked(photo: Boolean) = PickedFile(name, getSize() ?: 0L, photo) {
    val bytes = readBytes()
    if (photo) preparePhoto(name, bytes) { photoAsJpeg(it, PHOTO_MAX_PX, PHOTO_QUALITY) }
    else contentTypeOf(name)?.let { UploadFile(name, it, bytes) }
}

@Composable
fun ChatConversationScreen(
    state: ChatConversationContract.State,
    strings: Strings,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRetry: (String) -> Unit,
    onToggleComplaint: () -> Unit = {},
    onPick: (List<PickedFile>) -> Unit = {},
    onRemoveDraft: (String) -> Unit = {},
    onRetryDraft: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var showEmojiTray by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }

    // M7: three ways in — the camera, the gallery (several at once) and a PDF — each checked and uploaded by the view
    // model. The room left on the message caps what a picker lets her choose.
    val room = (MAX_ATTACHMENTS - state.drafts.size).coerceAtLeast(1)
    val galleryPicker = rememberFilePickerLauncher(type = PickerType.Image, mode = PickerMode.Multiple(maxItems = room)) { files ->
        if (!files.isNullOrEmpty()) onPick(files.map { it.picked(photo = true) })
    }
    val pdfPicker = rememberFilePickerLauncher(type = PickerType.File(listOf("pdf")), mode = PickerMode.Multiple(maxItems = room)) { files ->
        if (!files.isNullOrEmpty()) onPick(files.map { it.picked(photo = false) })
    }
    val camera = rememberCameraCapture { bytes ->
        if (bytes != null) {
            val name = "photo-${Today.epochMillis()}.jpg"
            onPick(listOf(PickedFile(name, bytes.size.toLong(), photo = true) { preparePhoto(name, bytes) { photoAsJpeg(it, PHOTO_MAX_PX, PHOTO_QUALITY) } }))
        }
    }

    val quickEmojis = remember {
        listOf(
            "😊", "👍", "❤️", "⭐", "🎉", "👏", "🙏", "🙌",
            "📚", "✏️", "📝", "📖", "🎓", "💯", "🏆", "📌",
            "👋", "🤔", "🤝", "🌟", "💪", "🎯", "🚀", "💡",
            "😃", "🤩", "✨", "📐", "🗓️", "🎈", "🥳", "🔥",
        )
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        // Conversation Top Bar
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .padding(horizontal = Dimens.s8, vertical = Dimens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = DashboardTokens.ink,
                )
            }
            Spacer(Modifier.width(Dimens.s4))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.withAdmin) strings.schoolAdministration else state.teacherName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DashboardTokens.ink,
                    )
                    if (state.topic == ChatTopic.COMPLAINT) {
                        Spacer(Modifier.width(Dimens.s8))
                        Chip(strings.complaintBadge, DashboardTokens.warningBg)
                        // A complaint has a status the staff side moves; the `status` frame keeps it current.
                        Spacer(Modifier.width(Dimens.s4))
                        Chip(
                            text = if (state.resolved) strings.statusResolved else strings.statusOpen,
                            color = if (state.resolved) DashboardTokens.successBg else MaterialTheme.colorScheme.primaryContainer,
                        )
                    }
                }
                if (!state.withAdmin) Text(
                    text = staffLabel(state.staffRole, state.subject, null, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
                // M4 (D6): presence is the peer's, from `peerOnline` and the `presence` frame — never this app's own
                // connection. Unknown shows nothing rather than a guess.
                presenceLine(state, strings)?.let { (label, live) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) DashboardTokens.success else DashboardTokens.inkSoft))
                        Spacer(Modifier.width(Dimens.s4))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isTeacherTyping) DashboardTokens.success else DashboardTokens.inkSoft,
                        )
                    }
                }
            }
        }

        if (state.resolved) {
            Row(
                Modifier.fillMaxWidth().background(DashboardTokens.successBg).padding(horizontal = Dimens.s16, vertical = Dimens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = resolvedBanner(state.resolver, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.ink,
                )
            }
        }

        // Message List
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AnimatedLoadingView("Loading conversation…")
                }
            } else if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Dimens.s24), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.withAdmin) strings.emptyConversationAdmin else when (state.staffRole) {
                            ChatStaffRole.COORDINATOR -> strings.emptyConversationCoordinator
                            ChatStaffRole.MANAGERIAL -> strings.emptyConversationManager
                            ChatStaffRole.TEACHER -> strings.emptyConversation
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = DashboardTokens.inkSoft,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.s16, vertical = Dimens.s8),
                    verticalArrangement = Arrangement.spacedBy(Dimens.s8),
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(msg = msg, strings = strings, onRetry = onRetry)
                    }
                }
            }
        }

        if (state.canMarkComplaint) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = Dimens.s12, vertical = Dimens.s8),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = state.markAsComplaint,
                        onCheckedChange = { onToggleComplaint() },
                        modifier = Modifier.semantics { contentDescription = strings.markAsComplaint },
                    )
                    Spacer(Modifier.width(Dimens.s8))
                    Text(strings.markAsComplaint, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink)
                }
                Text(
                    when (state.staffRole) {
                        ChatStaffRole.MANAGERIAL -> strings.markAsComplaintHintManager
                        ChatStaffRole.COORDINATOR -> strings.markAsComplaintHint
                        ChatStaffRole.TEACHER -> strings.markAsComplaintHintTeacher
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
        }

        DraftTray(state.drafts, state.refusal, strings, onRemove = onRemoveDraft, onRetry = onRetryDraft)

        // Emoji Tray
        if (showEmojiTray) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(quickEmojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable(role = Role.Button) {
                                    onInputChange(state.inputText + emoji)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(emoji, fontSize = 22.sp)
                        }
                    }
                }
            }
        }

        // Bottom Message Composer
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .padding(horizontal = Dimens.s8, vertical = Dimens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Emoji Tray Toggle
            IconButton(
                onClick = { showEmojiTray = !showEmojiTray },
                modifier = Modifier.size(40.dp),
            ) {
                Text(if (showEmojiTray) "⌨️" else "😊", fontSize = 20.sp)
            }

            // M7: the attach menu — camera (where there is one), gallery, PDF. Full once five files are on the message.
            Box {
                IconButton(
                    onClick = { showAttachMenu = true },
                    enabled = state.drafts.size < MAX_ATTACHMENTS,
                    modifier = Modifier.size(40.dp).semantics { contentDescription = strings.chatFiles.attach },
                ) {
                    Text("📎", fontSize = 20.sp)
                }
                DropdownMenu(expanded = showAttachMenu, onDismissRequest = { showAttachMenu = false }) {
                    if (camera != null) DropdownMenuItem(
                        text = { Text(strings.chatFiles.attachCamera) },
                        leadingIcon = { Text("📷") },
                        onClick = { showAttachMenu = false; camera() },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.chatFiles.attachGallery) },
                        leadingIcon = { Text("🖼️") },
                        onClick = { showAttachMenu = false; galleryPicker.launch() },
                    )
                    DropdownMenuItem(
                        text = { Text(strings.chatFiles.attachPdf) },
                        leadingIcon = { Text("📄") },
                        onClick = { showAttachMenu = false; pdfPicker.launch() },
                    )
                }
            }

            Spacer(Modifier.width(4.dp))

            OutlinedTextField(
                value = state.inputText,
                onValueChange = { if (it.length <= 2000) onInputChange(it) },
                placeholder = { Text(strings.typeMessage, color = DashboardTokens.inkSoft) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Spacer(Modifier.width(Dimens.s8))

            val canSend = state.canSend
            IconButton(
                onClick = {
                    showEmojiTray = false
                    onSend()
                },
                enabled = canSend,
                modifier = Modifier.size(44.dp).background(
                    if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = strings.send,
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else DashboardTokens.inkSoft,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatConversationContract.UiMessage,
    strings: Strings,
    onRetry: (String) -> Unit,
) {
    val isParent = msg.isFromParent
    val bubbleColor = if (isParent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val textColor = if (isParent) MaterialTheme.colorScheme.onPrimary else DashboardTokens.ink
    val shape = if (isParent) RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp) else RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isParent) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 280.dp)
                .background(bubbleColor, shape)
                .then(if (!isParent) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
                .padding(horizontal = Dimens.s12, vertical = Dimens.s8),
        ) {
            // M7 (B5): the files themselves — photos to look at, PDFs to open — not their names.
            if (msg.attachments.isNotEmpty()) {
                MessageAttachments(msg.attachments, strings)
                if (msg.body.isNotBlank()) Spacer(Modifier.height(Dimens.s8))
            }

            if (msg.body.isNotBlank()) {
                Text(
                    // M4 (D12): a message keeps its own direction inside the other language's screen. The body is
                    // plain text — a pre-B5 `[attachment:…]` tag included — and is never parsed.
                    text = isolate(msg.body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = textColor,
                )
            }

            Spacer(Modifier.height(Dimens.s4))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMessageTimestamp(msg.createdAt),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f) else DashboardTokens.inkSoft,
                )

                if (isParent) {
                    Spacer(Modifier.width(Dimens.s4))
                    when {
                        msg.isPending -> Text("🕒", fontSize = 10.sp)
                        msg.isFailed -> {
                            Row(
                                modifier = Modifier.clickable(role = Role.Button) { msg.clientId?.let(onRetry) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("⚠️", fontSize = 10.sp)
                                Spacer(Modifier.width(2.dp))
                                Text(
                                    strings.retry,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        msg.readAt != null -> Text("✓✓", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary)
                        else -> Text("✓", fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                }
            }
        }
    }
}

private fun formatMessageTimestamp(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    val hour = dt.hour.toString().padStart(2, '0')
    val minute = dt.minute.toString().padStart(2, '0')
    return "$hour:$minute"
}
