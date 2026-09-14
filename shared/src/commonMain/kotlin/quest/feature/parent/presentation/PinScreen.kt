package quest.feature.parent.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.SetPinUseCase
import quest.feature.parent.domain.VerifyPinUseCase

object PinContract {
    enum class Mode { LOADING, ENTER, CREATE, REPEAT }
    data class State(val mode: Mode = Mode.LOADING, val digits: String = "", val firstEntry: String = "", val error: Boolean = false, val mismatch: Boolean = false) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data class Digit(val d: Char) : Intent; data object Backspace : Intent }
    sealed interface Effect : MviEffect { data object Unlocked : Effect }
}

class PinViewModel(private val repo: ParentRepository, private val verify: VerifyPinUseCase, private val setPin: SetPinUseCase, private val changePin: Boolean = false) :
    MviViewModel<PinContract.State, PinContract.Intent, PinContract.Effect>(PinContract.State()) {
    init { dispatch(PinContract.Intent.Load) }

    override suspend fun handle(intent: PinContract.Intent) {
        when (intent) {
            PinContract.Intent.Load -> {
                val hasPin = repo.hasPin()
                reduce { copy(mode = if (!changePin && hasPin) PinContract.Mode.ENTER else PinContract.Mode.CREATE) }
            }
            PinContract.Intent.Backspace -> reduce { copy(digits = digits.dropLast(1), error = false) }
            is PinContract.Intent.Digit -> {
                if (current.digits.length >= 4) return
                val digits = current.digits + intent.d
                reduce { copy(digits = digits, error = false, mismatch = false) }
                if (digits.length == 4) submit(digits)
            }
        }
    }

    private suspend fun submit(digits: String) {
        when (current.mode) {
            PinContract.Mode.ENTER -> if (verify(digits)) effect(PinContract.Effect.Unlocked) else reduce { copy(digits = "", error = true) }
            PinContract.Mode.CREATE -> reduce { copy(mode = PinContract.Mode.REPEAT, firstEntry = digits, digits = "") }
            PinContract.Mode.REPEAT -> if (digits == current.firstEntry) { setPin(digits); effect(PinContract.Effect.Unlocked) }
            else reduce { copy(mode = PinContract.Mode.CREATE, firstEntry = "", digits = "", mismatch = true) }
            PinContract.Mode.LOADING -> Unit
        }
    }
}

@Composable
fun PinRoute(onUnlocked: () -> Unit, onBack: () -> Unit, changePin: Boolean = false) {
    val vm: PinViewModel = koinViewModel(key = "pin-$changePin") { org.koin.core.parameter.parametersOf(changePin) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is PinContract.Effect.Unlocked) onUnlocked() } }
    ParentShell(title = { it.grownUps }, onBack = onBack) { s -> PinScreen(state, vm::dispatch, s) }
}

@Composable
fun PinScreen(state: PinContract.State, dispatch: (PinContract.Intent) -> Unit, s: Strings) {
    Column(Modifier.fillMaxSize().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        val title = when (state.mode) {
            PinContract.Mode.ENTER -> s.enterPin
            PinContract.Mode.CREATE -> s.createPin
            PinContract.Mode.REPEAT -> s.repeatPin
            PinContract.Mode.LOADING -> ""
        }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s8))
        Text(
            when { state.error -> s.wrongPin; state.mismatch -> s.pinMismatch; else -> " " },
            style = MaterialTheme.typography.bodyMedium, color = Palette.coral, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.s24))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16), modifier = Modifier.semantics { contentDescription = "${state.digits.length} of 4 digits" }) {
            repeat(4) { i ->
                Box(Modifier.size(20.dp).border(2.dp, Palette.parentAccent, CircleShape).background(if (i < state.digits.length) Palette.parentAccent else Color.Transparent, CircleShape))
            }
        }
        Spacer(Modifier.height(Dimens.s32))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "⌫")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12), modifier = Modifier.padding(bottom = Dimens.s12)) {
                row.forEach { key ->
                    Box(
                        Modifier.size(76.dp).background(if (key.isEmpty()) Color.Transparent else Palette.parentSurface, CircleShape)
                            .then(if (key.isEmpty()) Modifier else Modifier.border(1.dp, Palette.parentLine, CircleShape).clickable(role = Role.Button) {
                                if (key == "⌫") dispatch(PinContract.Intent.Backspace) else dispatch(PinContract.Intent.Digit(key[0]))
                            })
                            .semantics { contentDescription = if (key == "⌫") "Delete" else key },
                        contentAlignment = Alignment.Center,
                    ) { Text(key, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk) }
                }
            }
        }
    }
}
