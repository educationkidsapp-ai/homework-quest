package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.auth.domain.SessionRoles
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

object StaffPasswordContract {
    data class State(
        val current: String = "",
        val next: String = "",
        val repeat: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) : MviState {
        val canSave: Boolean get() = !busy && current.isNotBlank() && next.length >= MIN && next == repeat
    }

    sealed interface Intent : MviIntent {
        data class Current(val v: String) : Intent
        data class Next(val v: String) : Intent
        data class Repeat(val v: String) : Intent
        data object Save : Intent
    }

    sealed interface Effect : MviEffect { data object Changed : Effect }

    /** The server's own floor for a staff password (`StaffAccounts`). */
    const val MIN = 8
}

/**
 * Feature 13: the temporary password the Admin handed her, replaced before she reaches the staff home. `/auth/change-password`
 * revokes every other session of hers, so the new password is used to sign in again at once (`StaffAuth`).
 */
class StaffPasswordViewModel(private val roles: SessionRoles) :
    MviViewModel<StaffPasswordContract.State, StaffPasswordContract.Intent, StaffPasswordContract.Effect>(StaffPasswordContract.State()) {

    override suspend fun handle(intent: StaffPasswordContract.Intent) {
        when (intent) {
            is StaffPasswordContract.Intent.Current -> reduce { copy(current = intent.v, error = null) }
            is StaffPasswordContract.Intent.Next -> reduce { copy(next = intent.v, error = null) }
            is StaffPasswordContract.Intent.Repeat -> reduce { copy(repeat = intent.v, error = null) }
            StaffPasswordContract.Intent.Save -> {
                if (!current.canSave) return
                reduce { copy(busy = true, error = null) }
                runCatching { roles.changeStaffPassword(current.current, current.next) }
                    .onSuccess { reduce { copy(busy = false) }; effect(StaffPasswordContract.Effect.Changed) }
                    .onFailure { e -> reduce { copy(busy = false, error = e.message ?: "Could not change the password.") } }
            }
        }
    }
}

@Composable
fun StaffChangePasswordRoute(onChanged: () -> Unit) {
    val vm: StaffPasswordViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is StaffPasswordContract.Effect.Changed) onChanged() } }
    StaffShell(title = { it.changePasswordTitle }, currentTab = null, onTabSelected = {}) { s, _ ->
        StaffChangePasswordScreen(state, s, vm::dispatch)
    }
}

@Composable
fun StaffChangePasswordScreen(
    state: StaffPasswordContract.State,
    strings: StaffStrings,
    dispatch: (StaffPasswordContract.Intent) -> Unit,
) {
    // hq-flag: none (the staff flag was already checked by GET /staff/me before this step is ever shown)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        ParentCard(Modifier.padding(top = Dimens.s8)) {
            Text(strings.changePasswordBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
        }
        Spacer(Modifier.height(Dimens.s16))
        PasswordField(state.current, strings.currentPassword) { dispatch(StaffPasswordContract.Intent.Current(it)) }
        Spacer(Modifier.height(Dimens.s12))
        PasswordField(state.next, strings.newPassword) { dispatch(StaffPasswordContract.Intent.Next(it)) }
        Spacer(Modifier.height(Dimens.s12))
        PasswordField(state.repeat, strings.repeatPassword) { dispatch(StaffPasswordContract.Intent.Repeat(it)) }
        val note = when {
            state.error != null -> state.error
            state.next.isNotEmpty() && state.next.length < StaffPasswordContract.MIN -> strings.passwordTooShort
            state.repeat.isNotEmpty() && state.next != state.repeat -> strings.passwordMismatch
            else -> null
        }
        if (note != null) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.error, modifier = Modifier.padding(top = Dimens.s8))
        }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(strings.changePassword, { dispatch(StaffPasswordContract.Intent.Save) }, enabled = state.canSave)
        Spacer(Modifier.height(Dimens.s24))
    }
}

@Composable
private fun PasswordField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
