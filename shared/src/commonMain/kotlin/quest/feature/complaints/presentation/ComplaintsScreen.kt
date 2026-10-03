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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ChatFrame
import quest.api.dto.Child
import quest.api.dto.Complaint
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.complaints.domain.applyStatus
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardTab
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * M8 — the Complaints tab (the owner, 2026-10-03: "Complaints must be separate from messages"). Her complaints about
 * the current child, the unread ones on top as the server sorts them, filtered Open / Resolved / All with the counts
 * of every one whatever the filter. New complaint is the only way to start one; Messages no longer can.
 */
object ComplaintsContract {
    enum class Filter(val wire: String?) { ALL(null), OPEN("open"), RESOLVED("resolved") }

    data class State(
        val loading: Boolean = true,
        val children: List<Child> = emptyList(),
        val childId: String? = null,
        val filter: Filter = Filter.ALL,
        val complaints: List<Complaint> = emptyList(),
        val open: Int = 0,
        val resolved: Int = 0,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class SelectChild(val id: String) : Intent
        data class SetFilter(val filter: Filter) : Intent
    }

    sealed interface Effect : MviEffect
}

class ComplaintsViewModel(
    private val children: ChildrenRepository,
    private val complaints: ComplaintsRepository,
    private val chat: ChatRepository,
) : MviViewModel<ComplaintsContract.State, ComplaintsContract.Intent, ComplaintsContract.Effect>(ComplaintsContract.State()) {

    override suspend fun handle(intent: ComplaintsContract.Intent) {
        when (intent) {
            ComplaintsContract.Intent.Load -> {
                val all = runCatching { children.children() }.getOrDefault(emptyList())
                val currentId = children.currentChild.value?.id ?: all.firstOrNull()?.id
                reduce { copy(children = all, childId = currentId) }
                chat.connect()
                load()
            }
            is ComplaintsContract.Intent.SelectChild -> {
                if (intent.id == current.childId) return
                children.select(intent.id)
                reduce { copy(childId = intent.id, loading = true, complaints = emptyList()) }
                load()
            }
            is ComplaintsContract.Intent.SetFilter -> {
                reduce { copy(filter = intent.filter, loading = true) }
                load()
            }
        }
    }

    private suspend fun load() {
        val childId = current.childId ?: run { reduce { copy(loading = false) }; return }
        try {
            val list = complaints.list(childId, current.filter.wire)
            reduce { copy(loading = false, childNotPlaced = false, errorMessage = null, complaints = list.complaints, open = list.open, resolved = list.resolved) }
        } catch (e: ApiException) {
            reduce { copy(loading = false, childNotPlaced = e.error.code == "child_not_placed", errorMessage = e.message, complaints = emptyList()) }
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message ?: "error", complaints = emptyList()) }
        }
    }

    init {
        // A `status` frame moves its row at once, then the list and both counts are read again — the complaint may sit
        // outside the current filter (reopened while she looks at Open), and only the server's counts cover every one.
        // B6: only a complaint has a status, so every `status` frame is one of hers. A message refreshes the list when
        // it is in a row on screen, or when the filter may be hiding its complaint.
        launch {
            chat.incomingFrames.collect { frame ->
                when (frame) {
                    is ChatFrame.Status -> {
                        reduce { copy(complaints = applyStatus(complaints, frame.threadId, frame.status, frame.at)) }
                        runCatching { load() }
                    }
                    is ChatFrame.Message ->
                        if (current.filter != ComplaintsContract.Filter.ALL || current.complaints.any { it.id == frame.message.threadId }) runCatching { load() }
                    else -> {}
                }
            }
        }
    }
}

@Composable
fun ComplaintsRoute(
    onBack: () -> Unit,
    onOpen: (Complaint) -> Unit,
    onNew: () -> Unit,
    onHome: () -> Unit = onBack,
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    val vm: ComplaintsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    // Shown, back from a complaint, or back from the background: the rows, their unread counts and statuses are read again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.dispatch(ComplaintsContract.Intent.Load) }

    FeatureGate(Flags.CHAT) {
        ParentShell(
            title = { it.complaints.title },
            onBack = onBack,
            currentTab = DashboardTab.COMPLAINTS,
            onTabSelected = { tab ->
                when (tab) {
                    DashboardTab.HOME -> onHome()
                    DashboardTab.NOTIFICATION -> onNotifications()
                    DashboardTab.MESSAGES -> onMessages()
                    DashboardTab.COMPLAINTS -> {}
                    DashboardTab.SETTINGS -> onSettings()
                }
            },
        ) { strings ->
            ComplaintsScreen(state, strings, onOpen = onOpen, onNew = onNew, dispatch = vm::dispatch)
        }
    }
}

@Composable
fun ComplaintsScreen(
    state: ComplaintsContract.State,
    strings: Strings,
    onOpen: (Complaint) -> Unit,
    onNew: () -> Unit,
    dispatch: (ComplaintsContract.Intent) -> Unit = {},
) {
    val c = strings.complaints
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        ParentButton(c.newComplaint, onNew)
        Spacer(Modifier.height(Dimens.s12))

        if (state.children.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                state.children.forEach { child ->
                    DashboardFilterChip(child.name, selected = child.id == state.childId, onClick = { dispatch(ComplaintsContract.Intent.SelectChild(child.id)) })
                }
            }
            Spacer(Modifier.height(Dimens.s8))
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            ComplaintsContract.Filter.entries.forEach { filter ->
                val label = when (filter) {
                    ComplaintsContract.Filter.ALL -> c.filterAll
                    ComplaintsContract.Filter.OPEN -> "${c.filterOpen} · ${state.open}"
                    ComplaintsContract.Filter.RESOLVED -> "${c.filterResolved} · ${state.resolved}"
                }
                DashboardFilterChip(label, selected = filter == state.filter, onClick = { dispatch(ComplaintsContract.Intent.SetFilter(filter)) })
            }
        }
        Spacer(Modifier.height(Dimens.s12))

        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            return
        }

        val problem = when {
            state.childNotPlaced -> strings.childNotPlaced
            state.errorMessage != null -> strings.somethingWrong
            state.complaints.isEmpty() -> when (state.filter) {
                ComplaintsContract.Filter.ALL -> c.empty
                ComplaintsContract.Filter.OPEN -> c.emptyOpen
                ComplaintsContract.Filter.RESOLVED -> c.emptyResolved
            }
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            }
            return
        }

        state.complaints.forEach { complaint -> ComplaintRow(complaint, strings, onClick = { onOpen(complaint) }) }
        Spacer(Modifier.height(Dimens.s16))
    }
}
