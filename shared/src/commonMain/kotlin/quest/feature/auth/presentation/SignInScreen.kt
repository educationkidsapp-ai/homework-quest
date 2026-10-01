package quest.feature.auth.presentation

import quest.ui.design.DashboardTokens
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.AuthProvider
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.school.presentation.LocalSchoolBranding
import quest.feature.school.presentation.SchoolLogo
import androidx.compose.ui.unit.dp
import quest.ui.design.Dimens

object SignInContract {
    data class State(val email: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null) : MviState
    sealed interface Intent : MviIntent {
        data class Email(val v: String) : Intent; data class Password(val v: String) : Intent
        data object Submit : Intent
    }
    sealed interface Effect : MviEffect { data object SignedIn : Effect }
}

class SignInViewModel(private val auth: AuthProvider) : MviViewModel<SignInContract.State, SignInContract.Intent, SignInContract.Effect>(SignInContract.State()) {
    override suspend fun handle(intent: SignInContract.Intent) {
        when (intent) {
            is SignInContract.Intent.Email -> reduce { copy(email = intent.v, error = null) }
            is SignInContract.Intent.Password -> reduce { copy(password = intent.v, error = null) }
            SignInContract.Intent.Submit -> run { auth.signIn(current.email, current.password) }
        }
    }

    private suspend fun run(block: suspend () -> Unit) {
        reduce { copy(busy = true, error = null) }
        runCatching { block() }.onSuccess { reduce { copy(busy = false) }; effect(SignInContract.Effect.SignedIn) }
            .onFailure { e -> reduce { copy(busy = false, error = e.message ?: "Could not sign in.") } }
    }
}

@Composable
fun SignInRoute(onSignedIn: () -> Unit) {
    val vm: SignInViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is SignInContract.Effect.SignedIn) onSignedIn() } }
    ParentShell(title = { it.signIn }, onBack = null) { s -> SignInScreen(state, s, vm::dispatch) }
}

@Composable
fun SignInScreen(state: SignInContract.State, s: Strings, dispatch: (SignInContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Dimens.s24))
        val branding = LocalSchoolBranding.current
        SchoolLogo(branding.logoUrl, branding.schoolName ?: branding.appName, size = 72.dp)
        Spacer(Modifier.height(Dimens.s12))
        Text(branding.appName, style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.ink)
        Spacer(Modifier.height(Dimens.s24))
        OutlinedTextField(state.email, { dispatch(SignInContract.Intent.Email(it)) }, Modifier.fillMaxWidth(), label = { Text(s.email) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(Dimens.s12))
        OutlinedTextField(state.password, { dispatch(SignInContract.Intent.Password(it)) }, Modifier.fillMaxWidth(), label = { Text(s.password) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        state.error?.let { Text(it, color = DashboardTokens.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8)) }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.signIn, { dispatch(SignInContract.Intent.Submit) }, enabled = !state.busy && state.email.isNotBlank() && state.password.isNotBlank())
        Spacer(Modifier.height(Dimens.s24))
    }
}
