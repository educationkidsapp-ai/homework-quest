package quest.feature.auth.presentation

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
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose

object SignInContract {
    data class State(val email: String = "", val password: String = "", val register: Boolean = false, val busy: Boolean = false, val error: String? = null) : MviState
    sealed interface Intent : MviIntent {
        data class Email(val v: String) : Intent; data class Password(val v: String) : Intent; data object ToggleMode : Intent
        data object Submit : Intent; data object Google : Intent
    }
    sealed interface Effect : MviEffect { data object SignedIn : Effect }
}

class SignInViewModel(private val auth: AuthProvider) : MviViewModel<SignInContract.State, SignInContract.Intent, SignInContract.Effect>(SignInContract.State()) {
    override suspend fun handle(intent: SignInContract.Intent) {
        when (intent) {
            is SignInContract.Intent.Email -> reduce { copy(email = intent.v, error = null) }
            is SignInContract.Intent.Password -> reduce { copy(password = intent.v, error = null) }
            SignInContract.Intent.ToggleMode -> reduce { copy(register = !register, error = null) }
            SignInContract.Intent.Submit -> run { if (current.register) auth.register(current.email, current.password) else auth.signIn(current.email, current.password) }
            SignInContract.Intent.Google -> run { auth.signInWithGoogle() }
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
    ParentShell(title = { if (state.register) it.register else it.signIn }, onBack = null) { s -> SignInScreen(state, s, vm::dispatch) }
}

@Composable
fun SignInScreen(state: SignInContract.State, s: Strings, dispatch: (SignInContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Dimens.s24))
        Pip(PipPose.WAVING, Dimens.pipMedium)
        Text("Homework Quest", style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk)
        Text(s.signInBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
        Spacer(Modifier.height(Dimens.s24))
        OutlinedTextField(state.email, { dispatch(SignInContract.Intent.Email(it)) }, Modifier.fillMaxWidth(), label = { Text(s.email) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(Dimens.s12))
        OutlinedTextField(state.password, { dispatch(SignInContract.Intent.Password(it)) }, Modifier.fillMaxWidth(), label = { Text(s.password) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        state.error?.let { Text(it, color = Palette.parentAccent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8)) }
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(if (state.register) s.createAccount else s.signIn, { dispatch(SignInContract.Intent.Submit) }, enabled = !state.busy && state.email.isNotBlank() && state.password.isNotBlank())
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(s.googleSignIn, { dispatch(SignInContract.Intent.Google) }, primary = false, enabled = !state.busy, icon = "G")
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(if (state.register) s.haveAccount else s.noAccount, { dispatch(SignInContract.Intent.ToggleMode) }, primary = false)
        Spacer(Modifier.height(Dimens.s24))
    }
}
