package quest.feature.chat.presentation

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
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
import quest.api.ApiException
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
import quest.ui.design.Dimens
import quest.ui.design.Palette
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
         * The `topic` this send carried, kept so a retry resends it. A failed first message is still in [State.messages],
         * which turns [State.canMarkComplaint] off — without this the toggle is gone and one transient 5xx would quietly
         * downgrade a parent's complaint to a question, with no way back.
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
        /** The server's own error code for the last refused send, so the screen picks the translated sentence. */
        val errorCode: String? = null,
        /**
         * The thread this conversation is, once one exists. `/ws/chat` is **one socket per parent** and fans out every
         * thread of hers, so a `status` frame has to be matched against this before it moves anything — otherwise a
         * coordinator resolving the Math complaint flips the banner inside an open English conversation.
         */
        val threadId: String? = null,
    ) : MviState {
        /**
         * The toggle is offered only while the thread does not exist yet, and never to a teacher: the server reads
         * `topic` on the message that *creates* a thread, so a later send cannot re-label one she has already worked on,
         * and `requireTopic` accepts `complaint` for a coordinator **or** a manager (RM4) and 400s for a teacher.
         *
         * `!loading` keeps it from flashing over an existing coordinator thread in the moment before its history lands.
         */
        val canMarkComplaint: Boolean
            get() = !loading && staffRole != ChatStaffRole.TEACHER && topic == ChatTopic.QUESTION && messages.isEmpty()
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class UpdateInput(val text: String) : Intent
        data object SendMessage : Intent
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
            is ChatConversationContract.Intent.RetrySend -> retrySend(intent.clientId)
            ChatConversationContract.Intent.ToggleComplaint -> reduce { copy(markAsComplaint = !markAsComplaint, errorCode = null) }
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

    private suspend fun sendMessage() {
        val body = current.inputText.trim()
        if (body.isBlank() || body.length > 2000) return

        // `topic` only says anything on the message that creates the thread, so it rides on the first send alone.
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

        reduce { copy(inputText = "", errorCode = null, messages = messages + pendingMsg) }

        try {
            val confirmed = chat.sendMessage(childId, teacherId, body, clientId, sentTopic)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                copy(
                    messages = updated,
                    topic = if (opening) ChatTopic.COMPLAINT else topic,
                    markAsComplaint = false,
                    threadId = confirmed.threadId,
                )
            }
        } catch (e: Throwable) {
            val code = (e as? ApiException)?.error?.code
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) msg.copy(isPending = false, isFailed = true) else msg
                }
                copy(messages = updated, errorCode = code)
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
            // The original `topic` goes out again: a retried first message is still the one that creates the thread,
            // and dropping it here would file the parent's complaint as an ordinary question.
            val confirmed = chat.sendMessage(childId, teacherId, target.body, clientId, target.topic)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                copy(
                    messages = updated,
                    topic = target.topic ?: topic,
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
                )
            }
        }
    }
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
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().background(Palette.parentBg)) {
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
                    tint = Palette.parentInk,
                )
            }
            Spacer(Modifier.width(Dimens.s4))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.teacherName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Palette.parentInk,
                    )
                    if (state.topic == ChatTopic.COMPLAINT) {
                        Spacer(Modifier.width(Dimens.s8))
                        Chip(strings.complaintBadge, Palette.sun)
                    }
                }
                // R8: whose side of the school this is. A row from a pre-R4 server says "Teacher", as it always did.
                Text(
                    text = staffLabel(state.staffRole, state.subject, null, strings),
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.parentInkSoft,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val isOnline = state.connectionState == ChatConnectionState.CONNECTED
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            if (state.isTeacherTyping) Palette.mint
                            else if (isOnline) Palette.mint
                            else Palette.parentInkSoft
                        )
                    )
                    Spacer(Modifier.width(Dimens.s4))
                    Text(
                        text = if (state.isTeacherTyping) strings.isTyping
                               else if (isOnline) strings.online
                               else strings.offline,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isTeacherTyping) Palette.mint else Palette.parentInkSoft,
                    )
                }
            }
        }

        // A resolved thread is still hers to write in — the server refuses nothing, so the composer stays live and
        // the banner is the whole of it (R8). The `status` frame flips this without a refetch.
        if (state.resolved) {
            Row(
                Modifier.fillMaxWidth().background(Palette.mint).padding(horizontal = Dimens.s16, vertical = Dimens.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.resolvedBanner,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.parentInk,
                )
            }
        }

        if (state.errorCode == COMPLAINT_NEEDS_COORDINATOR) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
            ) {
                Text(
                    text = strings.complaintNeedsCoordinator,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // Message List
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Dimens.s24), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (state.staffRole) {
                            ChatStaffRole.COORDINATOR -> strings.emptyConversationCoordinator
                            ChatStaffRole.MANAGERIAL -> strings.emptyConversationManager
                            ChatStaffRole.TEACHER -> strings.emptyConversation
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.parentInkSoft,
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

        // The complaint toggle, offered only while this send would create the thread (state.canMarkComplaint).
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
                    Text(strings.markAsComplaint, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk)
                }
                Text(
                    strings.markAsComplaintHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.parentInkSoft,
                )
            }
        }

        // Bottom Message Composer
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
                .padding(horizontal = Dimens.s12, vertical = Dimens.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.inputText,
                onValueChange = { if (it.length <= 2000) onInputChange(it) },
                placeholder = { Text(strings.typeMessage, color = Palette.parentInkSoft) },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )

            Spacer(Modifier.width(Dimens.s8))

            val canSend = state.inputText.trim().isNotBlank() && state.inputText.length <= 2000
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.size(48.dp).background(
                    if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = strings.send,
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else Palette.parentInkSoft,
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
    val textColor = if (isParent) MaterialTheme.colorScheme.onPrimary else Palette.parentInk
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
            Text(
                text = msg.body,
                style = MaterialTheme.typography.bodyLarge,
                color = textColor,
            )

            Spacer(Modifier.height(Dimens.s4))

            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMessageTimestamp(msg.createdAt),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = if (isParent) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f) else Palette.parentInkSoft,
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
