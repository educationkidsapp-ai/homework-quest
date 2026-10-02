package quest.feature.children.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.domain.SignOutUseCase
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.StudentAvatar

// hq-flag: none (the list of the parent's own children is how every other screen is reached; no school can turn it off)

object ChildrenContract {
    data class State(val loading: Boolean = true, val children: List<Child> = emptyList(), val currentId: String? = null) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Pick(val id: String) : Intent
        data object SignOut : Intent
    }
    sealed interface Effect : MviEffect { data object Picked : Effect; data object SignedOut : Effect }
}

/**
 * The children the school linked to this parent. The app cannot add one — the admin does — so this screen only lists
 * what `GET /children` answers, and says so when the answer is empty.
 */
class ChildrenViewModel(private val children: ChildrenRepository, private val signOut: SignOutUseCase) :
    MviViewModel<ChildrenContract.State, ChildrenContract.Intent, ChildrenContract.Effect>(ChildrenContract.State()) {
    override suspend fun handle(intent: ChildrenContract.Intent) {
        when (intent) {
            ChildrenContract.Intent.Load -> {
                reduce { copy(loading = true) }
                val list = children.refresh()
                val current = children.currentChild.value?.id
                reduce { copy(loading = false, children = list, currentId = current) }
            }
            is ChildrenContract.Intent.Pick -> { children.select(intent.id); effect(ChildrenContract.Effect.Picked) }
            ChildrenContract.Intent.SignOut -> { signOut(); effect(ChildrenContract.Effect.SignedOut) }
        }
    }
}

@Composable
fun ChildPickerRoute(onPicked: () -> Unit, onSignedOut: () -> Unit, onBack: (() -> Unit)?) {
    val vm: ChildrenViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(ChildrenContract.Intent.Load)
        vm.effects.collect {
            when (it) {
                ChildrenContract.Effect.Picked -> onPicked()
                ChildrenContract.Effect.SignedOut -> onSignedOut()
            }
        }
    }
    ParentShell(title = { it.children }, onBack = onBack) { s -> ChildPickerScreen(state, s, vm::dispatch) }
}

/** Every child the school linked to the parent; tapping one opens that child's home. */
@Composable
fun ChildPickerScreen(state: ChildrenContract.State, s: Strings, dispatch: (ChildrenContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        Spacer(Modifier.height(Dimens.s8))
        when {
            state.loading -> AnimatedDotsLoader(modifier = Modifier.align(Alignment.CenterHorizontally).padding(Dimens.s24))
            state.children.isEmpty() -> NoChildrenLinked(s) { dispatch(ChildrenContract.Intent.Load) }
            else -> {
                Text(s.chooseStudent, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft, modifier = Modifier.padding(bottom = Dimens.s12))
                state.children.forEach { c -> ChildRow(c, s, selected = c.id == state.currentId, onClick = { dispatch(ChildrenContract.Intent.Pick(c.id)) }) }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.signOut, { dispatch(ChildrenContract.Intent.SignOut) }, primary = false)
        Spacer(Modifier.height(Dimens.s24))
    }
}

/** One linked child: initial, name, course. Shared by this list and the parent home. */
@Composable
fun ChildRow(child: Child, s: Strings, selected: Boolean, onClick: () -> Unit) {
    ParentCard(Modifier.padding(bottom = 10.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StudentAvatar(child.name)
            Spacer(Modifier.width(Dimens.s12))
            Column(Modifier.weight(1f)) {
                Text(child.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.inkStrong)
                Text(
                    "${if (child.curriculum == Curriculum.BRITISH) s.british else s.american} · ${s.grade} ${child.grade}",
                    style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft,
                )
            }
            if (selected) DashboardPill(text = s.currentChild, variant = DashboardPillVariant.SUCCESS)
        }
    }
}

/** The school has linked nobody yet: say whose move it is, and offer to ask again. */
@Composable
fun NoChildrenLinked(s: Strings, onRetry: () -> Unit) {
    ParentCard {
        Text(s.noChildrenLinked, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkStrong)
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(s.checkAgain, onRetry, primary = false)
    }
}
