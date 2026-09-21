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
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.parent.domain.ParentRepository
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
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class UpdateInput(val text: String) : Intent
        data object SendMessage : Intent
        data class RetrySend(val clientId: String) : Intent
    }

    sealed interface Effect : MviEffect
}

class ChatConversationViewModel(
    private val childId: String,
    private val teacherId: String,
    private val teacherName: String,
    private val chat: ChatRepository,
) : MviViewModel<ChatConversationContract.State, ChatConversationContract.Intent, ChatConversationContract.Effect>(
    ChatConversationContract.State(childId = childId, teacherId = teacherId, teacherName = teacherName)
) {
    private var typingJob: Job? = null
    private var lastTypingSentMillis: Long = 0L

    override suspend fun handle(intent: ChatConversationContract.Intent) {
        when (intent) {
            ChatConversationContract.Intent.Load -> loadInitialMessages()
            is ChatConversationContract.Intent.UpdateInput -> handleInputChanged(intent.text)
            ChatConversationContract.Intent.SendMessage -> sendMessage()
            is ChatConversationContract.Intent.RetrySend -> retrySend(intent.clientId)
        }
    }

    private suspend fun loadInitialMessages() {
        chat.connect()
        try {
            val history = chat.messages(childId, teacherId)
            val uiList = history.map { it.toUiMessage() }
            reduce { copy(loading = false, messages = uiList) }
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

        val clientId = Ids.random()
        val pendingMsg = ChatConversationContract.UiMessage(
            id = clientId,
            body = body,
            isFromParent = true,
            createdAt = Today.epochMillis(),
            isPending = true,
            clientId = clientId,
        )

        reduce { copy(inputText = "", messages = messages + pendingMsg) }

        try {
            val confirmed = chat.sendMessage(childId, teacherId, body, clientId)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                copy(messages = updated)
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
            val confirmed = chat.sendMessage(childId, teacherId, target.body, clientId)
            reduce {
                val updated = messages.map { msg ->
                    if (msg.clientId == clientId) confirmed.toUiMessage(isPending = false) else msg
                }
                copy(messages = updated)
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
                        if (frame.readBy == ChatSender.TEACHER) {
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
                        if (frame.from == ChatSender.TEACHER) {
                            reduce { copy(isTeacherTyping = true) }
                            typingJob?.cancel()
                            typingJob = launch {
                                delay(4_500L)
                                reduce { copy(isTeacherTyping = false) }
                            }
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
    childId: String,
    teacherId: String,
    teacherName: String,
    onBack: () -> Unit,
) {
    val vm: ChatConversationViewModel = koinViewModel { parametersOf(childId, teacherId, teacherName) }
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
                Text(
                    text = state.teacherName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Palette.parentInk,
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

        // Message List
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (state.messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(Dimens.s24), contentAlignment = Alignment.Center) {
                    Text(
                        text = strings.emptyConversation,
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
