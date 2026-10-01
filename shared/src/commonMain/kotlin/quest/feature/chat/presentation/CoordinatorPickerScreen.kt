package quest.feature.chat.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import quest.core.runCancellable
import quest.api.dto.ChatTopic
import quest.api.dto.ChatStaffRole
import quest.api.dto.Child
import quest.ui.design.DashboardFilterChip
import androidx.compose.foundation.horizontalScroll
import quest.ui.design.DashboardTokens
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import quest.ui.design.AnimatedDotsLoader
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.ChatThread
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.Dimens

/**
 * New message (R8, RM4, M1): the parent picks the **child**, says whether it is a **question or a complaint**, then
 * picks **who** — a teacher of a subject (`GET /children/{id}/chat/threads`), a subject coordinator
 * (`…/coordinators`) or the department manager (`…/managers`), all decided server-side from the child's section.
 * A complaint may go to any of the three.
 *
 * The three calls are independent: a school with a coordinator and no manager (or the other way round) still gets the
 * part it has, because one 404 must not empty a list another endpoint answered. A row whose `id` is null has no thread
 * behind it; the first message the parent sends creates one. The topic chosen here travels with her next message.
 */
object CoordinatorPickerContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        /** Every child linked to the parent, and the one this message is about. */
        val children: List<Child> = emptyList(),
        val childId: String? = null,
        val complaint: Boolean = false,
        val teachers: List<ChatThread> = emptyList(),
        val coordinators: List<ChatThread> = emptyList(),
        val managers: List<ChatThread> = emptyList(),
        /** The child's track, so a manager row reads "Department manager · British" rather than naming a class. */
        val curriculum: Curriculum? = null,
    ) : MviState {
        val isEmpty: Boolean get() = teachers.isEmpty() && coordinators.isEmpty() && managers.isEmpty()
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class SelectChild(val id: String) : Intent
        data class SetComplaint(val on: Boolean) : Intent
    }

    sealed interface Effect : MviEffect
}

class CoordinatorPickerViewModel(
    private val children: ChildrenRepository,
    private val chat: ChatRepository,
) : MviViewModel<CoordinatorPickerContract.State, CoordinatorPickerContract.Intent, CoordinatorPickerContract.Effect>(
    CoordinatorPickerContract.State()
) {
    override suspend fun handle(intent: CoordinatorPickerContract.Intent) {
        when (intent) {
            CoordinatorPickerContract.Intent.Load -> {
                val all = children.children()
                val child = children.currentChild.value ?: all.firstOrNull()
                reduce { copy(children = all) }
                load(child)
            }
            is CoordinatorPickerContract.Intent.SelectChild -> {
                reduce { copy(loading = true) }
                load(current.children.firstOrNull { it.id == intent.id })
            }
            is CoordinatorPickerContract.Intent.SetComplaint -> reduce { copy(complaint = intent.on) }
        }
    }

    private suspend fun load(child: Child?) {
        if (child == null) {
            reduce { copy(loading = false, childId = null, teachers = emptyList(), coordinators = emptyList(), managers = emptyList()) }
            return
        }
        // The thread list also carries the coordinator and manager threads she already has; here it is asked only
        // for the teachers, because the other two endpoints list everybody she *may* write to.
        val teachers = runCancellable { chat.threads(child.id).filter { it.staffRole == ChatStaffRole.TEACHER } }
        val coordinators = runCancellable { chat.coordinators(child.id) }
        val managers = runCancellable { chat.managers(child.id) }
        val results = listOf(teachers, coordinators, managers)
        // "This school has none" is a 404, and only a 404: a 500 from one endpoint while another answers must not
        // render as an empty section, because "nobody" and "we could not ask" are different answers.
        val failure = results.mapNotNull { it.exceptionOrNull() }.firstOrNull { !it.isAbsence() }
            ?: results.firstNotNullOfOrNull { it.exceptionOrNull() }?.takeIf { results.all { r -> r.isFailure } }
        // An unplaced child has nobody to write to at all, and the server says so on each of the three routes.
        val notPlaced = results.all { it.getOrNull().isNullOrEmpty() } &&
            results.any { (it.exceptionOrNull() as? ApiException)?.error?.code == "child_not_placed" }
        reduce {
            copy(
                loading = false,
                childId = child.id,
                childNotPlaced = notPlaced,
                errorMessage = if (failure == null || notPlaced) null else failure.message,
                teachers = teachers.getOrDefault(emptyList()),
                coordinators = coordinators.getOrDefault(emptyList()),
                // The parent answers the school administration but never starts a thread with it.
                managers = managers.getOrDefault(emptyList()).filter { it.withAdmin != true },
                curriculum = child.curriculum,
            )
        }
    }
}

@Composable
fun CoordinatorPickerRoute(
    onBack: () -> Unit,
    onOpenConversation: (ChatThread, complaint: Boolean) -> Unit,
) {
    val vm: CoordinatorPickerViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.dispatch(CoordinatorPickerContract.Intent.Load) }

    FeatureGate(Flags.CHAT) {
        ParentShell(title = { it.newMessage }, onBack = onBack) { strings ->
            CoordinatorPickerScreen(
                state = state, strings = strings,
                // "Complaint" opens the conversation with its toggle on, for a new thread and an existing one alike.
                onSelect = { row -> onOpenConversation(row, state.complaint) },
                dispatch = vm::dispatch,
            )
        }
    }
}

@Composable
fun CoordinatorPickerScreen(
    state: CoordinatorPickerContract.State,
    strings: Strings,
    onSelect: (ChatThread) -> Unit,
    dispatch: (CoordinatorPickerContract.Intent) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        // 1 — the child
        if (state.children.isNotEmpty()) {
            SectionTitle(strings.newMessageChild)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                state.children.forEach { c ->
                    DashboardFilterChip(c.name, selected = c.id == state.childId, onClick = { dispatch(CoordinatorPickerContract.Intent.SelectChild(c.id)) })
                }
            }
        }

        // 2 — a question or a complaint
        SectionTitle(strings.newMessageTopic)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            DashboardFilterChip(strings.topicQuestion, selected = !state.complaint, onClick = { dispatch(CoordinatorPickerContract.Intent.SetComplaint(false)) })
            DashboardFilterChip(strings.topicComplaint, selected = state.complaint, onClick = { dispatch(CoordinatorPickerContract.Intent.SetComplaint(true)) })
        }
        if (state.complaint) {
            Text(strings.complaintExplained, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s8))
        }

        // 3 — who
        SectionTitle(strings.pickCoordinator)
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            return
        }
        val problem = when {
            state.childNotPlaced -> strings.childNotPlaced
            state.errorMessage != null -> strings.somethingWrong
            state.isEmpty -> strings.newMessageNobody
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            }
            return
        }
        RecipientGroup(strings.teachersGroup, state.teachers, strings, onSelect)
        RecipientGroup(strings.coordinatorsGroup, state.coordinators, strings, onSelect)
        RecipientGroup(strings.managersGroup, state.managers, strings, onSelect, department = departmentWord(state.curriculum, strings))
        Spacer(Modifier.height(Dimens.s16))
    }
}

@Composable
private fun RecipientGroup(title: String, rows: List<ChatThread>, strings: Strings, onSelect: (ChatThread) -> Unit, department: String? = null) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s8, bottom = Dimens.s8))
    // An existing thread shows its badge and status, so a parent sees which conversations are already complaints.
    rows.forEach { row -> ChatThreadRow(row, strings, { onSelect(row) }, showStatus = row.topic == ChatTopic.COMPLAINT, department = department) }
}

/**
 * The two answers that mean "there is nobody of this kind here", as opposed to "we could not ask": the route is off
 * or empty (404) and the child has no class yet. Anything else is a failure the parent is told about.
 */
private fun Throwable.isAbsence(): Boolean =
    (this as? ApiException)?.error?.code in setOf(ApiError.NOT_FOUND, "child_not_placed")

/** The department a manager speaks for, in the parent's language — the child's own track. */
fun departmentWord(curriculum: Curriculum?, strings: Strings): String? = when (curriculum) {
    Curriculum.BRITISH -> strings.british
    Curriculum.AMERICAN -> strings.american
    null -> null
}
