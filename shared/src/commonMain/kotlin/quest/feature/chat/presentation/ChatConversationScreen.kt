package quest.feature.chat.presentation

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
import quest.feature.chat.domain.ChatRepository
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.featureEnabled
import androidx.compose.ui.text.style.TextOverflow
import io.github.vinceglb.filekit.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.core.PickerMode
import io.github.vinceglb.filekit.core.PickerType
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
    )

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
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class UpdateInput(val text: String) : Intent
        data object SendMessage : Intent
        data class SendCustom(val body: String) : Intent
        data class RetrySend(val clientId: String) : Intent
        data object ToggleComplaint : Intent
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
    )
) {
    private val childId get() = peer.childId
    private val teacherId get() = peer.staffId

    private var typingJob: Job? = null
    private var lastTypingSentMillis: Long = 0L

    override suspend fun handle(intent: ChatConversationContract.Intent) {
        when (intent) {
            ChatConversationContract.Intent.Load -> loadInitialMessages()
            is ChatConversationContract.Intent.UpdateInput -> handleInputChanged(intent.text)
            ChatConversationContract.Intent.SendMessage -> sendMessage()
            is ChatConversationContract.Intent.SendCustom -> sendMessage(intent.body)
            is ChatConversationContract.Intent.RetrySend -> retrySend(intent.clientId)
            ChatConversationContract.Intent.ToggleComplaint -> reduce { copy(markAsComplaint = !markAsComplaint) }
        }
    }

    private suspend fun loadInitialMessages() {
        chat.connect()
        try {
            val history = chat.messages(childId, teacherId)
            val uiList = history.map { it.toUiMessage() }
            reduce { copy(loading = false, messages = uiList, threadId = history.firstOrNull()?.threadId ?: threadId) }
            chat.markRead(childId, teacherId)
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

    private suspend fun sendMessage(customBody: String? = null) {
        val body = (customBody ?: current.inputText).trim()
        if (body.isBlank() || body.length > 2000) return

        // The topic rides on exactly one send — the one made while the toggle is on. The toggle goes off with it, so a
        // failed send does not mark the next message as well; its retry carries the topic the message kept.
        val opening = current.canMarkComplaint && current.markAsComplaint
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
        )

        reduce { copy(inputText = "", markAsComplaint = false, messages = messages + pendingMsg) }

        try {
            val confirmed = chat.sendMessage(childId, teacherId, body, clientId, sentTopic)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                // A thread that has just become a complaint is an open one, whatever it was before.
                copy(
                    messages = updated,
                    topic = if (opening) ChatTopic.COMPLAINT else topic,
                    resolved = if (opening) false else resolved,
                    threadId = confirmed.threadId,
                )
            }
        } catch (_: Throwable) {
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) msg.copy(isPending = false, isFailed = true) else msg
                }
                copy(messages = updated)
            }
        }
    }

    private suspend fun retrySend(clientId: String) {
        val target = current.messages.firstOrNull { it.clientId == clientId } ?: return
        reduce {
            val updated = messages.map { msg ->
                if (msg.clientId == clientId) msg.copy(isPending = true, isFailed = false) else msg
            }
            copy(messages = updated)
        }

        try {
            // The original `topic` goes out again: dropping it here would file the parent's complaint as an ordinary
            // question.
            val confirmed = chat.sendMessage(childId, teacherId, target.body, clientId, target.topic)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                copy(
                    messages = updated,
                    topic = target.topic ?: topic,
                    resolved = if (target.topic == ChatTopic.COMPLAINT) false else resolved,
                    threadId = confirmed.threadId,
                )
            }
        } catch (_: Throwable) {
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) msg.copy(isPending = false, isFailed = true) else msg
                }
                copy(messages = updated)
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
                        if (frame.from == ChatSender.TEACHER && isOurs(frame.threadId)) {
                            reduce { copy(isTeacherTyping = true) }
                            typingJob?.cancel()
                            typingJob = launch {
                                delay(4_500L)
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

    /**
     * Whether a per-thread frame belongs to the conversation on screen.
     *
     * `/ws/chat` is one socket per parent and fans out **every** thread of hers, so `status`, `read` and `typing` all
     * arrive here for conversations this screen is not showing. Without this, a coordinator resolving the Math
     * complaint flips the Resolved banner inside an open English thread (and the same for read ticks and the typing
     * dot). A null [State.threadId] means no thread exists yet, so nothing that names one can be about us.
     */
    private fun isOurs(frameThreadId: String): Boolean = current.threadId == frameThreadId

    private fun ChatMessage.toUiMessage(isPending: Boolean = false): ChatConversationContract.UiMessage =
        ChatConversationContract.UiMessage(
            id = id,
            body = body,
            isFromParent = sender == ChatSender.PARENT,
            createdAt = createdAt,
            readAt = readAt,
            isPending = isPending,
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
                    onSendCustom = { vm.dispatch(ChatConversationContract.Intent.SendCustom(it)) },
                )
            }
        }
    }
}

data class AttachedFile(
    val name: String,
    val size: Long,
    val type: String, // "image" or "pdf"
)

data class ParsedMessage(
    val text: String,
    val attachment: AttachmentMeta? = null,
)

data class AttachmentMeta(
    val id: String,
    val type: String,
    val name: String,
    val size: String,
)

fun parseMessageBody(body: String): ParsedMessage {
    val tagRegex = Regex("""\[attachment:([^:]+):(image|pdf):([^:]+):([^\]]+)\]""")
    val match = tagRegex.find(body)
    if (match != null) {
        val (id, type, name, size) = match.destructured
        val cleanText = body.replace(match.value, "").trim()
        return ParsedMessage(
            text = cleanText,
            attachment = AttachmentMeta(id, type, name, size),
        )
    }

    val imgRegex = Regex("""!\[([^\]]*)\]\(([^)]+)\)""")
    val imgMatch = imgRegex.find(body)
    if (imgMatch != null) {
        val alt = imgMatch.groupValues[1].ifBlank { "Image" }
        val cleanText = body.replace(imgMatch.value, "").trim()
        return ParsedMessage(
            text = cleanText,
            attachment = AttachmentMeta(id = "img", type = "image", name = alt, size = "Image"),
        )
    }

    val pdfRegex = Regex("""\[([^\]]+)\]\(([^)]+\.pdf[^)]*)\)""")
    val pdfMatch = pdfRegex.find(body)
    if (pdfMatch != null) {
        val name = pdfMatch.groupValues[1]
        val cleanText = body.replace(pdfMatch.value, "").trim()
        return ParsedMessage(
            text = cleanText,
            attachment = AttachmentMeta(id = "pdf", type = "pdf", name = name, size = "PDF"),
        )
    }

    return ParsedMessage(text = body)
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
    onSendCustom: (String) -> Unit = { onSend() },
) {
    val listState = rememberLazyListState()
    var attachedFile by remember { mutableStateOf<AttachedFile?>(null) }
    var showEmojiTray by remember { mutableStateOf(false) }

    val filePicker = rememberFilePickerLauncher(
        type = PickerType.File(listOf("png", "jpg", "jpeg", "webp", "pdf")),
        mode = PickerMode.Single,
    ) { platformFile ->
        if (platformFile != null) {
            val ext = platformFile.name.substringAfterLast('.', "").lowercase()
            val isPdf = ext == "pdf"
            val type = if (isPdf) "pdf" else "image"
            val size = platformFile.getSize() ?: 0L
            attachedFile = AttachedFile(
                name = platformFile.name,
                size = size,
                type = type,
            )
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

    Column(Modifier.fillMaxSize().background(DashboardTokens.bg).safeDrawingPadding()) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isOnline = state.connectionState == ChatConnectionState.CONNECTED
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            if (state.isTeacherTyping) DashboardTokens.success
                            else if (isOnline) DashboardTokens.success
                            else DashboardTokens.inkSoft
                        )
                    )
                    Spacer(Modifier.width(Dimens.s4))
                    Text(
                        text = if (state.isTeacherTyping) strings.isTyping
                               else if (isOnline) strings.online
                               else strings.offline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isTeacherTyping) DashboardTokens.success else DashboardTokens.inkSoft,
                    )
                }
            }
        }

        if (state.resolved) {
            Row(
                Modifier.fillMaxWidth().background(DashboardTokens.successBg).padding(horizontal = Dimens.s16, vertical = Dimens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.resolvedBanner,
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

        // Attached File Preview Chip
        if (attachedFile != null) {
            val file = attachedFile!!
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = Dimens.s12, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (file.type == "pdf") "📄" else "🖼️", fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = DashboardTokens.inkStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sizeKb = "${(file.size / 1024).coerceAtLeast(1)} KB"
                        Text(
                            text = "${if (file.type == "pdf") "PDF" else "Image"} • $sizeKb",
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardTokens.inkSoft,
                        )
                    }
                    IconButton(
                        onClick = { attachedFile = null },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Text("✕", color = DashboardTokens.inkSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }

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

            // Attachment Picker
            IconButton(
                onClick = { filePicker.launch() },
                modifier = Modifier.size(40.dp),
            ) {
                Text("📎", fontSize = 20.sp)
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

            val hasAttachment = attachedFile != null
            val hasText = state.inputText.trim().isNotBlank() && state.inputText.length <= 2000
            val canSend = hasAttachment || hasText

            IconButton(
                onClick = {
                    if (canSend) {
                        val body = if (attachedFile != null) {
                            val att = attachedFile!!
                            val sizeKb = "${(att.size / 1024).coerceAtLeast(1)} KB"
                            val id = "att-${Today.epochMillis()}"
                            val tag = "[attachment:$id:${att.type}:${att.name}:$sizeKb]"
                            if (state.inputText.isNotBlank()) "$tag ${state.inputText.trim()}" else tag
                        } else {
                            state.inputText.trim()
                        }
                        attachedFile = null
                        showEmojiTray = false
                        onSendCustom(body)
                    }
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
    val parsed = remember(msg.body) { parseMessageBody(msg.body) }

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
            // Render attachment card if present
            if (parsed.attachment != null) {
                val att = parsed.attachment
                if (att.type == "pdf") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f) else DashboardTokens.errorBg)
                            .border(1.dp, if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f) else DashboardTokens.errorBorder, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(DashboardTokens.error, RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("PDF", color = DashboardTokens.onBrand, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "PDF • ${att.size}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = if (isParent) textColor.copy(alpha = 0.8f) else DashboardTokens.inkSoft,
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f) else MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🖼️", fontSize = 24.sp)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = att.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = textColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Image • ${att.size}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = if (isParent) textColor.copy(alpha = 0.8f) else DashboardTokens.inkSoft,
                                )
                            }
                        }
                    }
                }
                if (parsed.text.isNotBlank()) {
                    Spacer(Modifier.height(Dimens.s8))
                }
            }

            if (parsed.text.isNotBlank()) {
                Text(
                    text = parsed.text,
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
