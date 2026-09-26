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
import quest.api.dto.ChatThread
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.Dimens
import quest.ui.design.Palette

/**
 * R8 (DR3): where a parent starts a conversation with a subject coordinator. The rows come from
 * `GET /children/{id}/coordinators` — the coordinators whose scope covers the subjects taught in the current child's
 * section, decided entirely server-side from `staff_scopes`. Nothing here has a status yet: a row whose `id` is null
 * has no thread behind it, and the first message the parent sends is what creates one.
 */
object CoordinatorPickerContract {
    data class State(
        val loading: Boolean = true,
        val childNotPlaced: Boolean = false,
        val errorMessage: String? = null,
        val coordinators: List<ChatThread> = emptyList(),
    ) : MviState

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
            reduce { copy(loading = false, coordinators = emptyList()) }
            return
        }
        try {
            val list = chat.coordinators(child.id)
            reduce { copy(loading = false, childNotPlaced = false, errorMessage = null, coordinators = list) }
        } catch (e: ApiException) {
            val notPlaced = e.error.code == "child_not_placed"
            reduce {
                copy(
                    loading = false,
                    childNotPlaced = notPlaced,
                    errorMessage = if (notPlaced) null else e.message,
                    coordinators = emptyList(),
                )
            }
        } catch (e: Throwable) {
            reduce { copy(loading = false, errorMessage = e.message, coordinators = emptyList()) }
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
        ParentShell(title = { it.messageCoordinator }, onBack = onBack) { strings ->
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
            state.coordinators.isEmpty() -> strings.noCoordinators
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
        state.coordinators.forEach { row ->
            ChatThreadRow(row, strings, { onSelect(row) }, showStatus = false)
        }
    }
}
