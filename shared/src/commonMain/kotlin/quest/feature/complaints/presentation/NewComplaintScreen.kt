package quest.feature.complaints.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.Child
import quest.api.dto.Complaint
import quest.api.dto.ComplaintRecipient
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Ids
import quest.feature.chat.presentation.departmentWord
import quest.feature.children.domain.ChildrenRepository
import androidx.lifecycle.viewModelScope
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.AttachmentUploader
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.presentation.AttachMenuButton
import quest.feature.chat.presentation.AttachmentDraft
import quest.feature.chat.presentation.AttachmentDrafts
import quest.feature.chat.presentation.DraftTray
import quest.feature.chat.presentation.DraftsState
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.domain.RecipientGroup
import quest.feature.complaints.domain.groupOf
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * M8 — New complaint: the **child**, **whom** it is for (`GET /children/{id}/complaints/recipients` — her teachers,
 * the coordinators of its subjects and the department manager, decided by the server), a short **title** and the
 * first **message**. Sent, it opens the complaint itself.
 */
object NewComplaintContract {
    data class State(
        val loading: Boolean = true,
        val children: List<Child> = emptyList(),
        val childId: String? = null,
        val curriculum: Curriculum? = null,
        val recipients: List<ComplaintRecipient> = emptyList(),
        val recipientId: String? = null,
        val title: String = "",
        val body: String = "",
        val sending: Boolean = false,
        val childNotPlaced: Boolean = false,
        val loadFailed: Boolean = false,
        val sendFailed: Boolean = false,
        /** B5: photos and PDFs for the first message, uploaded as soon as they are picked. */
        val drafts: List<AttachmentDraft> = emptyList(),
        val refusal: AttachmentRefusal? = null,
    ) : MviState {
        /** A message may be only files (B5), and goes only once every file is up. */
        val canSend: Boolean
            get() = !sending && recipientId != null && title.isNotBlank() && (body.isNotBlank() || drafts.isNotEmpty()) &&
                drafts.all { it.ref != null } && title.trim().length <= ComplaintsRepository.TITLE_MAX && body.trim().length <= ComplaintsRepository.BODY_MAX
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class SelectChild(val id: String) : Intent
        data class SelectRecipient(val staffId: String) : Intent
        data class SetTitle(val text: String) : Intent
        data class SetBody(val text: String) : Intent
        data object Send : Intent
        /** M7's picker (camera, gallery, PDF), its photos re-encoded without metadata before they go up. */
        data class AddFiles(val files: List<PickedFile>) : Intent
        data class RemoveDraft(val localId: String) : Intent
        data class RetryDraft(val localId: String) : Intent
    }

    sealed interface Effect : MviEffect {
        data class Created(val complaint: Complaint) : Effect
    }
}

class NewComplaintViewModel(
    private val children: ChildrenRepository,
    private val complaints: ComplaintsRepository,
    staging: UploadStaging,
    uploader: AttachmentUploader,
) : MviViewModel<NewComplaintContract.State, NewComplaintContract.Intent, NewComplaintContract.Effect>(NewComplaintContract.State()) {

    /** M7's attachment pipeline — the same as Messages': re-encode, strip, stage, stream, clean up. */
    private val files = AttachmentDrafts(
        scope = viewModelScope,
        staging = staging,
        upload = { file, onProgress -> uploader.upload(current.childId ?: error("no child"), file, onProgress) },
        read = { DraftsState(current.drafts, current.refusal) },
        write = { change -> reduce { val next = DraftsState(drafts, refusal).change(); copy(drafts = next.drafts, refusal = next.refusal) } },
    )

    override fun onCleared() {
        files.clear()
        super.onCleared()
    }

    /**
     * One id for this complaint's first message, reused by every retry of Send: the socket echo of a send whose answer
     * was lost carries the same id, so it is recognisable as this complaint rather than a second one.
     */
    private var clientId = Ids.random()

    override suspend fun handle(intent: NewComplaintContract.Intent) {
        when (intent) {
            NewComplaintContract.Intent.Load -> {
                val all = runCatching { children.children() }.getOrDefault(emptyList())
                val child = children.currentChild.value ?: all.firstOrNull()
                reduce { copy(children = all) }
                load(child)
            }
            is NewComplaintContract.Intent.SelectChild -> {
                if (intent.id == current.childId) return
                // An upload is bound to the child it was made for: switching child starts the tray again.
                files.clear()
                reduce { copy(loading = true, recipientId = null, drafts = emptyList(), refusal = null) }
                load(current.children.firstOrNull { it.id == intent.id })
            }
            is NewComplaintContract.Intent.SelectRecipient -> reduce { copy(recipientId = intent.staffId) }
            is NewComplaintContract.Intent.SetTitle -> reduce { copy(title = intent.text.take(ComplaintsRepository.TITLE_MAX), sendFailed = false) }
            is NewComplaintContract.Intent.SetBody -> reduce { copy(body = intent.text.take(ComplaintsRepository.BODY_MAX), sendFailed = false) }
            NewComplaintContract.Intent.Send -> send()
            is NewComplaintContract.Intent.AddFiles -> if (current.childId != null) files.add(intent.files)
            is NewComplaintContract.Intent.RemoveDraft -> files.remove(intent.localId)
            is NewComplaintContract.Intent.RetryDraft -> files.retry(intent.localId)
        }
    }

    private suspend fun load(child: Child?) {
        if (child == null) {
            reduce { copy(loading = false, childId = null, recipients = emptyList()) }
            return
        }
        try {
            val list = complaints.recipients(child.id)
            reduce { copy(loading = false, childId = child.id, curriculum = child.curriculum, recipients = list, childNotPlaced = false, loadFailed = false) }
        } catch (e: ApiException) {
            reduce {
                copy(loading = false, childId = child.id, curriculum = child.curriculum, recipients = emptyList(),
                    childNotPlaced = e.error.code == "child_not_placed", loadFailed = e.error.code != "child_not_placed")
            }
        } catch (_: Throwable) {
            reduce { copy(loading = false, childId = child.id, recipients = emptyList(), loadFailed = true) }
        }
    }

    private suspend fun send() {
        val state = current
        val childId = state.childId ?: return
        val staffId = state.recipientId ?: return
        if (!state.canSend) return
        reduce { copy(sending = true, sendFailed = false) }
        try {
            val detail = complaints.create(childId, staffId, state.title, state.body, clientId, state.drafts.mapNotNull { it.ref?.id })
            files.sent()
            reduce { copy(sending = false) }
            effect(NewComplaintContract.Effect.Created(detail.complaint))
        } catch (_: Throwable) {
            reduce { copy(sending = false, sendFailed = true) }
        }
    }
}

@Composable
fun NewComplaintRoute(onBack: () -> Unit, onCreated: (Complaint) -> Unit) {
    val vm: NewComplaintViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(NewComplaintContract.Intent.Load)
        vm.effects.collect { if (it is NewComplaintContract.Effect.Created) onCreated(it.complaint) }
    }
    FeatureGate(Flags.CHAT) {
        ParentShell(title = { it.complaints.newComplaint }, onBack = onBack) { strings ->
            NewComplaintScreen(state, strings, vm::dispatch)
        }
    }
}

@Composable
fun NewComplaintScreen(state: NewComplaintContract.State, strings: Strings, dispatch: (NewComplaintContract.Intent) -> Unit = {}) {
    val c = strings.complaints
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        Text(c.explained, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)

        // 1 — the child
        if (state.children.isNotEmpty()) {
            SectionTitle(c.aboutChild)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                state.children.forEach { child ->
                    DashboardFilterChip(child.name, selected = child.id == state.childId, onClick = { dispatch(NewComplaintContract.Intent.SelectChild(child.id)) })
                }
            }
        }

        // 2 — whom it is for
        SectionTitle(c.toWhom)
        when {
            state.loading -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            state.childNotPlaced || state.loadFailed || state.recipients.isEmpty() -> ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(
                    when {
                        state.childNotPlaced -> strings.childNotPlaced
                        state.loadFailed -> strings.somethingWrong
                        else -> c.nobody
                    },
                    style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft,
                )
            }
            else -> {
                val department = departmentWord(state.curriculum, strings)
                val groups = state.recipients.groupBy { groupOf(it.peerRole) }
                listOf(
                    RecipientGroup.TEACHER to strings.teachersGroup,
                    RecipientGroup.COORDINATOR to strings.coordinatorsGroup,
                    RecipientGroup.MANAGER to strings.managersGroup,
                ).forEach { (group, heading) ->
                    val rows = groups[group].orEmpty()
                    if (rows.isEmpty()) return@forEach
                    Text(heading, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s8, bottom = Dimens.s4))
                    rows.forEach { recipient ->
                        RecipientOption(recipient, selected = recipient.staffId == state.recipientId, strings = strings, department = department) {
                            dispatch(NewComplaintContract.Intent.SelectRecipient(recipient.staffId))
                        }
                    }
                }
            }
        }

        // 3 — the title and the first message
        SectionTitle(c.titleLabel)
        OutlinedTextField(
            value = state.title,
            onValueChange = { dispatch(NewComplaintContract.Intent.SetTitle(it)) },
            placeholder = { Text(c.titleHint, color = DashboardTokens.inkSoft) },
            singleLine = true,
            supportingText = { Text("${state.title.length} / ${ComplaintsRepository.TITLE_MAX}", color = DashboardTokens.inkSoft) },
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors(),
        )
        SectionTitle(c.messageLabel)
        OutlinedTextField(
            value = state.body,
            onValueChange = { dispatch(NewComplaintContract.Intent.SetBody(it)) },
            placeholder = { Text(c.messageHint, color = DashboardTokens.inkSoft) },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            colors = fieldColors(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            AttachMenuButton(state.drafts.size, strings, onPick = { dispatch(NewComplaintContract.Intent.AddFiles(it)) })
            Text(strings.chatFiles.attach, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
        }
        DraftTray(state.drafts, state.refusal, strings, onRemove = { dispatch(NewComplaintContract.Intent.RemoveDraft(it)) }, onRetry = { dispatch(NewComplaintContract.Intent.RetryDraft(it)) })

        if (state.sendFailed) {
            Spacer(Modifier.height(Dimens.s8))
            Text(c.sendFailed, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
        }
        Spacer(Modifier.height(Dimens.s16))
        ParentButton(c.send, onClick = { dispatch(NewComplaintContract.Intent.Send) }, enabled = state.canSend)
        Spacer(Modifier.height(Dimens.s24))
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
)

/** One person she may complain to, chosen like a radio option — the whole row is the target. */
@Composable
private fun RecipientOption(recipient: ComplaintRecipient, selected: Boolean, strings: Strings, department: String?, onSelect: () -> Unit) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f).padding(start = Dimens.s8)) {
                Text(recipient.name, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.ink, fontWeight = FontWeight.SemiBold)
                val label = recipientLabel(recipient.peerRole, recipient.subject, strings)
                Text(
                    if (groupOf(recipient.peerRole) == RecipientGroup.MANAGER && department != null) "$label · $department" else label,
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
        }
    }
}
