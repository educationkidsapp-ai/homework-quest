package quest.feature.chat.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import quest.ui.design.Palette

/**
 * R8 (DR3), widened by RM4 (DR5): where a parent starts a conversation with somebody who is not one of her child's
 * teachers. Two headed sections — the subject coordinators from `GET /children/{id}/coordinators` and the department
 * manager from `GET /children/{id}/managers` — both decided entirely server-side from `staff_scopes`. Nothing here has
 * a status yet: a row whose `id` is null has no thread behind it, and the first message the parent sends is what
 * creates one.
 *
 * The two calls are independent: a school with a coordinator and no manager (or the other way round) still gets the
 * half it has, because one 404 must not empty a list the other endpoint answered.
 */
object CoordinatorPickerContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val coordinators: List<ChatThread> = emptyList(),
        val managers: List<ChatThread> = emptyList(),
        /** The child's track, so a manager row reads "Department manager · British" rather than naming a class. */
        val curriculum: Curriculum? = null,
    ) : MviState {
        val isEmpty: Boolean get() = coordinators.isEmpty() && managers.isEmpty()
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
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
            CoordinatorPickerContract.Intent.Load -> load()
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, coordinators = emptyList(), managers = emptyList()) }
            return
        }
        val coordinators = runCatching { chat.coordinators(child.id) }
        val managers = runCatching { chat.managers(child.id) }
        // "This school has none" is a 404, and only a 404: a 500 from one endpoint while the other answers must not
        // render as an empty section, because "no coordinator" and "we could not ask" are different answers.
        val failure = listOfNotNull(coordinators.exceptionOrNull(), managers.exceptionOrNull()).firstOrNull { !it.isAbsence() }
            ?: (coordinators.exceptionOrNull() ?: managers.exceptionOrNull())?.takeIf { coordinators.isFailure && managers.isFailure }
        val notPlaced = (failure as? ApiException)?.error?.code == "child_not_placed"
        reduce {
            copy(
                loading = false,
                childNotPlaced = notPlaced,
                errorMessage = if (failure == null || notPlaced) null else failure.message,
                coordinators = coordinators.getOrDefault(emptyList()),
                managers = managers.getOrDefault(emptyList()),
                curriculum = child.curriculum,
            )
        }
    }
}

@Composable
fun CoordinatorPickerRoute(
    onBack: () -> Unit,
    onOpenConversation: (ChatThread) -> Unit,
) {
    val vm: CoordinatorPickerViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.dispatch(CoordinatorPickerContract.Intent.Load) }

    FeatureGate(Flags.CHAT) {
        ParentShell(title = { it.messageStaff }, onBack = onBack) { strings ->
            CoordinatorPickerScreen(state = state, strings = strings, onSelect = onOpenConversation)
        }
    }
}

@Composable
fun CoordinatorPickerScreen(
    state: CoordinatorPickerContract.State,
    strings: Strings,
    onSelect: (ChatThread) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            return
        }

        val problem = when {
            state.childNotPlaced -> strings.childNotPlaced
            state.errorMessage != null -> strings.somethingWrong
            state.isEmpty -> strings.noCoordinators
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft)
            }
            return
        }

        Text(
            strings.pickCoordinator,
            style = MaterialTheme.typography.bodyLarge,
            color = Palette.parentInk,
            modifier = Modifier.padding(vertical = Dimens.s8),
        )
        if (state.coordinators.isNotEmpty()) {
            SectionTitle(strings.coordinatorsGroup)
            state.coordinators.forEach { row ->
                ChatThreadRow(row, strings, { onSelect(row) }, showStatus = false)
            }
        }
        if (state.managers.isNotEmpty()) {
            SectionTitle(strings.managersGroup)
            val department = departmentWord(state.curriculum, strings)
            state.managers.forEach { row ->
                ChatThreadRow(row, strings, { onSelect(row) }, showStatus = false, department = department)
            }
        }
    }
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
