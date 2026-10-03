package quest.feature.complaints.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatThreadStatus
import quest.api.dto.Complaint
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintEvent
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Ids
import quest.core.platform.Today
import quest.core.text.isolate
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import androidx.lifecycle.viewModelScope
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.AttachmentUploader
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.domain.asChatAttachment
import quest.feature.chat.presentation.AttachMenuButton
import quest.feature.chat.presentation.AttachmentDraft
import quest.feature.chat.presentation.AttachmentDrafts
import quest.feature.chat.presentation.DraftTray
import quest.feature.chat.presentation.DraftsState
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.domain.TimelineItem
import quest.feature.complaints.domain.canReopen
import quest.feature.complaints.domain.timeline
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * M8 — one complaint: its messages and its status changes as system lines ("Resolved by Ms. Lina · 3 October"), a
 * reply box, and **Reopen** while it is resolved. The parent never resolves — that is the staff side's (B6: a
 * `resolved` from her is 403), so there is no such button here. Live from `/ws/chat`: a `message` frame named by this
 * complaint's id lands in the list, and a `status` frame re-reads the complaint for who moved it and when.
 */
object ComplaintContract {
    data class State(
        val childId: String = "",
        val complaintId: String = "",
        val loading: Boolean = true,
        val complaint: Complaint? = null,
        val messages: List<TimelineItem.Message> = emptyList(),
        val events: List<ComplaintEvent> = emptyList(),
        val input: String = "",
        val reopening: Boolean = false,
        /** The last Reopen did not reach the server: said in a band above the conversation, nothing else changed. */
        val reopenFailed: Boolean = false,
        /** B5: photos and PDFs for the next reply, uploaded as soon as they are picked. */
        val drafts: List<AttachmentDraft> = emptyList(),
        val refusal: AttachmentRefusal? = null,
        val gone: Boolean = false,
        val errorMessage: String? = null,
    ) : MviState {
        val items: List<TimelineItem> get() = timeline(messages, events)
        val canReply: Boolean get() = complaint?.canReply == true
        val showReopen: Boolean get() = complaint?.let(::canReopen) == true
        val resolved: Boolean get() = complaint?.status == ChatThreadStatus.RESOLVED
        /** Words, or at least one uploaded file — and no upload still on its way or failed. */
        val canSend: Boolean get() = canReply && drafts.all { it.ref != null } && input.length <= ComplaintsRepository.BODY_MAX &&
            (input.isNotBlank() || drafts.isNotEmpty())
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class SetInput(val text: String) : Intent
        data object Send : Intent
        data class Retry(val clientId: String) : Intent
        data object Reopen : Intent
        /** M7's picker (camera, gallery, PDF), its photos re-encoded without metadata before they go up. */
        data class AddFiles(val files: List<PickedFile>) : Intent
        data class RemoveDraft(val localId: String) : Intent
        data class RetryDraft(val localId: String) : Intent
    }

    sealed interface Effect : MviEffect
}

class ComplaintViewModel(
    private val childId: String,
    private val complaintId: String,
    private val complaints: ComplaintsRepository,
    private val chat: ChatRepository,
    staging: UploadStaging,
    uploader: AttachmentUploader,
) : MviViewModel<ComplaintContract.State, ComplaintContract.Intent, ComplaintContract.Effect>(
    ComplaintContract.State(childId = childId, complaintId = complaintId),
) {
    /** M7's attachment pipeline — the same as Messages': re-encode, strip, stage, stream, clean up. */
    private val files = AttachmentDrafts(
        scope = viewModelScope,
        staging = staging,
        upload = { file, onProgress -> uploader.upload(childId, file, onProgress) },
        read = { DraftsState(current.drafts, current.refusal) },
        write = { change -> reduce { val next = DraftsState(drafts, refusal).change(); copy(drafts = next.drafts, refusal = next.refusal) } },
    )

    override fun onCleared() {
        files.clear()
        super.onCleared()
    }

    override suspend fun handle(intent: ComplaintContract.Intent) {
        when (intent) {
            ComplaintContract.Intent.Load -> { chat.connect(); load() }
            is ComplaintContract.Intent.SetInput -> reduce { copy(input = intent.text.take(ComplaintsRepository.BODY_MAX)) }
            ComplaintContract.Intent.Send -> send()
            is ComplaintContract.Intent.Retry -> retry(intent.clientId)
            ComplaintContract.Intent.Reopen -> reopen()
            is ComplaintContract.Intent.AddFiles -> files.add(intent.files)
            is ComplaintContract.Intent.RemoveDraft -> files.remove(intent.localId)
            is ComplaintContract.Intent.RetryDraft -> files.retry(intent.localId)
        }
    }

    private suspend fun load() {
        try {
            show(complaints.detail(childId, complaintId))
            markRead()
        } catch (e: ApiException) {
            reduce { copy(loading = false, gone = e.error.code == ApiError.NOT_FOUND, errorMessage = e.message) }
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message ?: "error") }
        }
    }

    /** A fresh read keeps the replies still on their way (pending or failed) at the end, where she left them. */
    private fun show(detail: ComplaintDetail) = reduce {
        val confirmed = detail.messages.map { TimelineItem.Message(it) }
        val unsent = messages.filter { (it.pending || it.failed) && confirmed.none { c -> c.message.id == it.message.id } }
        copy(loading = false, gone = false, errorMessage = null, complaint = detail.complaint, messages = confirmed + unsent, events = detail.events)
    }

    private suspend fun markRead() {
        if (current.messages.any { it.message.sender != ChatSender.PARENT && it.message.readAt == null } || (current.complaint?.unread ?: 0) > 0) {
            runCatching { complaints.markRead(childId, complaintId) }
        }
    }

    private suspend fun send() {
        if (!current.canSend) return
        val body = current.input.trim()
        val attached = current.drafts.mapNotNull { it.ref?.asChatAttachment() }
        val clientId = Ids.random()
        val draft = ChatMessage(clientId, complaintId, ChatSender.PARENT, "", body, Today.epochMillis(), attachments = attached.ifEmpty { null })
        files.sent()
        reduce { copy(input = "", messages = messages + TimelineItem.Message(draft, pending = true, clientId = clientId)) }
        deliver(clientId, body, attached.map { it.id })
    }

    private suspend fun retry(clientId: String) {
        val target = current.messages.firstOrNull { it.clientId == clientId && it.failed } ?: return
        reduce { copy(messages = messages.map { if (it.clientId == clientId) it.copy(pending = true, failed = false) else it }) }
        // The files ride along again: the message keeps them, so a retry is the same message.
        deliver(clientId, target.message.body, target.message.attachments.orEmpty().map { it.id })
    }

    private suspend fun deliver(clientId: String, body: String, attachmentIds: List<String>) {
        try {
            val sent = complaints.reply(childId, complaintId, body, clientId, attachmentIds)
            reduce {
                // The socket's echo may have landed first; one copy of the message either way.
                val others = messages.filter { it.clientId != clientId && it.message.id != sent.id }
                copy(messages = others + TimelineItem.Message(sent))
            }
        } catch (_: Throwable) {
            reduce { copy(messages = messages.map { if (it.clientId == clientId) it.copy(pending = false, failed = true) else it }) }
        }
    }

    private suspend fun reopen() {
        if (!current.showReopen || current.reopening) return
        reduce { copy(reopening = true, reopenFailed = false) }
        try {
            val reopened = complaints.reopen(childId, complaintId)
            reduce { copy(reopening = false, complaint = reopened.copy(lastMessage = complaint?.lastMessage)) }
            // Her reopen is an event too: read it back so the "Reopened by you" line appears where it happened.
            runCatching { complaints.detail(childId, complaintId) }.getOrNull()?.let { show(it) }
        } catch (_: Throwable) {
            reduce { copy(reopening = false, reopenFailed = true) }
        }
    }

    init {
        launch {
            chat.incomingFrames.collect { frame ->
                when (frame) {
                    // Matched by the complaint's id: the same staff member's Messages thread is a different id.
                    is ChatFrame.Message -> if (frame.message.threadId == complaintId) {
                        val msg = frame.message
                        reduce {
                            val acked = frame.clientId?.let { id -> messages.any { it.clientId == id } } == true
                            when {
                                acked -> copy(messages = messages.map { if (it.clientId == frame.clientId) TimelineItem.Message(msg) else it })
                                messages.any { it.message.id == msg.id } -> this
                                else -> copy(messages = messages + TimelineItem.Message(msg))
                            }
                        }
                        if (msg.sender != ChatSender.PARENT) runCatching { complaints.markRead(childId, complaintId) }
                    }
                    is ChatFrame.Status -> if (frame.threadId == complaintId) {
                        reduce { copy(complaint = complaint?.copy(status = frame.status)) }
                        runCatching { complaints.detail(childId, complaintId) }.getOrNull()?.let { show(it) }
                    }
                    else -> {}
                }
            }
        }
        // Back online: whatever was said meanwhile is read again.
        launch {
            var previous = chat.connectionState.value
            chat.connectionState.collect { state ->
                if (previous != ChatConnectionState.CONNECTED && state == ChatConnectionState.CONNECTED && !current.loading) {
                    runCatching { complaints.detail(childId, complaintId) }.getOrNull()?.let { show(it) }
                }
                previous = state
            }
        }
    }
}

@Composable
fun ComplaintRoute(childId: String, complaintId: String, onBack: () -> Unit) {
    val vm: ComplaintViewModel = koinViewModel(key = "complaint-$complaintId") { parametersOf(childId, complaintId) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(ComplaintContract.Intent.Load) }
    FeatureGate(Flags.CHAT) {
        ParentShell(title = { s -> state.complaint?.title ?: s.complaints.title }, onBack = onBack) { strings ->
            ComplaintScreen(state, strings, vm::dispatch)
        }
    }
}

@Composable
fun ComplaintScreen(state: ComplaintContract.State, strings: Strings, dispatch: (ComplaintContract.Intent) -> Unit = {}) {
    val c = strings.complaints
    val listState = rememberLazyListState()
    val items = state.items
    LaunchedEffect(items.size) { if (items.isNotEmpty()) listState.animateScrollToItem(items.size - 1) }

    Column(Modifier.fillMaxSize()) {
        val complaint = state.complaint
        if (complaint != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${c.to.replace("{name}", isolate(complaint.recipientName))} · ${recipientLabel(complaint.recipientRole, complaint.subject, strings)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTokens.inkSoft,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Dimens.s8))
                ComplaintStatusChip(complaint.status, strings)
            }
        }
        if (state.resolved) {
            Column(Modifier.fillMaxWidth().background(DashboardTokens.successBg).padding(horizontal = Dimens.s16, vertical = Dimens.s12)) {
                Text(c.resolvedNote, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
                if (state.reopenFailed) {
                    Spacer(Modifier.height(Dimens.s8))
                    Text(
                        c.reopenFailed,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.ink,
                        modifier = Modifier.fillMaxWidth()
                            .background(DashboardTokens.errorBg, RoundedCornerShape(DashboardTokens.radiusSm))
                            .border(1.dp, DashboardTokens.errorBorder, RoundedCornerShape(DashboardTokens.radiusSm))
                            .padding(horizontal = Dimens.s12, vertical = Dimens.s8)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (state.showReopen) {
                    Spacer(Modifier.height(Dimens.s8))
                    ParentButton(c.reopen, onClick = { dispatch(ComplaintContract.Intent.Reopen) }, primary = false, enabled = !state.reopening)
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp) }
                complaint == null -> ParentCard(Modifier.padding(Dimens.s16)) {
                    Text(if (state.gone) strings.notices.itemGone else strings.somethingWrong, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = Dimens.s16),
                    verticalArrangement = Arrangement.spacedBy(Dimens.s8),
                ) {
                    item { Spacer(Modifier.height(Dimens.s4)) }
                    items(items, key = { it.key }) { item ->
                        when (item) {
                            is TimelineItem.Message -> ComplaintBubble(item, strings) { dispatch(ComplaintContract.Intent.Retry(it)) }
                            is TimelineItem.Event -> ComplaintEventLine(item.event, strings)
                        }
                    }
                    item { Spacer(Modifier.height(Dimens.s4)) }
                }
            }
        }

        if (complaint != null && !state.canReply) {
            Text(c.readOnly, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(Dimens.s16))
        } else if (complaint != null) {
            Composer(state, strings, dispatch)
        }
    }
}

@Composable
private fun Composer(state: ComplaintContract.State, strings: Strings, dispatch: (ComplaintContract.Intent) -> Unit) {
    val canSend = state.canSend
    val sendLabel = strings.send
    val onSend = { dispatch(ComplaintContract.Intent.Send) }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RectangleShape)
            .padding(horizontal = Dimens.s8, vertical = Dimens.s8),
    ) {
    DraftTray(state.drafts, state.refusal, strings, onRemove = { dispatch(ComplaintContract.Intent.RemoveDraft(it)) }, onRetry = { dispatch(ComplaintContract.Intent.RetryDraft(it)) })
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AttachMenuButton(state.drafts.size, strings, onPick = { dispatch(ComplaintContract.Intent.AddFiles(it)) })
        OutlinedTextField(
            value = state.input,
            onValueChange = { dispatch(ComplaintContract.Intent.SetInput(it)) },
            placeholder = { Text(strings.complaints.reply, color = DashboardTokens.inkSoft) },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
        Spacer(Modifier.width(Dimens.s8))
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
                contentDescription = sendLabel,
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else DashboardTokens.inkSoft,
            )
        }
    }
    }
}
