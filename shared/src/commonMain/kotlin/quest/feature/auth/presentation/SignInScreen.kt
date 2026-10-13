package quest.feature.auth.presentation

import androidx.compose.ui.platform.testTag
import quest.ui.design.TestTags
import quest.ui.design.MySchoolMark
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
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
import quest.feature.auth.domain.SessionRoles
import quest.feature.staff.domain.RoleRouter
import quest.feature.staff.presentation.StaffStrings
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.Strings
import quest.feature.parent.presentation.displayName
import quest.feature.school.presentation.LocalSchoolBranding
import quest.feature.school.presentation.SchoolLogo
import quest.ui.design.Dimens

object SignInContract {
    data class State(
        val email: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null,
        /**
         * Feature 13: her credentials were right but her school has `staffMobileApp` off and she has no parent side,
         * so there is nowhere to land. Said in one line rather than as an error.
         */
        val staffDisabled: Boolean = false,
    ) : MviState
    sealed interface Intent : MviIntent {
        data class Email(val v: String) : Intent; data class Password(val v: String) : Intent
        data object Submit : Intent
    }
    sealed interface Effect : MviEffect {
        /**
         * Feature 13: one screen signs both sides in, so the effect says where to go — [start] is what
         * `RoleRouter` decided, and [mustChangePassword] puts the change-password step in front of it.
         */
        data class SignedIn(val start: RoleRouter.Start, val mustChangePassword: Boolean) : Effect
    }
}

/**
 * Feature 13: one sign-in screen for everybody. The credentials go to the staff sign-in and to Firebase, in that
 * order, and [SessionRoles] answers with the sides that took them; this view model only turns that into "where next".
 */
class SignInViewModel(private val roles: SessionRoles) : MviViewModel<SignInContract.State, SignInContract.Intent, SignInContract.Effect>(SignInContract.State()) {
    override suspend fun handle(intent: SignInContract.Intent) {
        when (intent) {
            is SignInContract.Intent.Email -> reduce { copy(email = intent.v, error = null, staffDisabled = false) }
            is SignInContract.Intent.Password -> reduce { copy(password = intent.v, error = null, staffDisabled = false) }
            SignInContract.Intent.Submit -> submit()
        }
    }

    private suspend fun submit() {
        reduce { copy(busy = true, error = null, staffDisabled = false) }
        val outcome = runCatching { roles.signIn(current.email, current.password) }
        val result = outcome.getOrNull()
        if (result == null) {
            val message = outcome.exceptionOrNull()?.message ?: "Could not sign in."
            reduce { copy(busy = false, error = message) }
            return
        }
        reduce { copy(busy = false, error = result.error, staffDisabled = result.staffDisabled && !result.signedIn) }
        if (!result.signedIn) return
        effect(SignInContract.Effect.SignedIn(RoleRouter.start(result.sides, roles.side.value), result.mustChangePassword))
    }
}

@Composable
fun SignInRoute(onSignedIn: (SignInContract.Effect.SignedIn) -> Unit) {
    val vm: SignInViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is SignInContract.Effect.SignedIn) onSignedIn(it) } }
    ParentShell(title = { it.signIn }, onBack = null) { s -> SignInScreen(state, s, vm::dispatch) }
}

@Composable
fun SignInScreen(state: SignInContract.State, s: Strings, dispatch: (SignInContract.Intent) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(Dimens.s24))
        val branding = LocalSchoolBranding.current
        // A school's own logo when it has one; otherwise the product's mark. The school name never borrows our mark.
        if (branding.logoUrl != null || branding.schoolName != null) SchoolLogo(branding.logoUrl, branding.schoolName ?: branding.displayName(s), size = 72.dp)
        else MySchoolMark(size = 88.dp, contentDescription = branding.displayName(s))
        Spacer(Modifier.height(Dimens.s12))
        Text(branding.displayName(s), style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.ink)
        Spacer(Modifier.height(Dimens.s24))
        OutlinedTextField(state.email, { dispatch(SignInContract.Intent.Email(it)) }, Modifier.fillMaxWidth().testTag(TestTags.SIGN_IN_EMAIL), label = { Text(s.emailOrUsername) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        Spacer(Modifier.height(Dimens.s12))
        OutlinedTextField(state.password, { dispatch(SignInContract.Intent.Password(it)) }, Modifier.fillMaxWidth().testTag(TestTags.SIGN_IN_PASSWORD), label = { Text(s.password) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        state.error?.let { Text(it, color = DashboardTokens.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8)) }
        // Feature 13: her school has not been given the staff app, and she has no parent side to fall back to.
        if (state.staffDisabled) Text(
            StaffStrings.forLanguage(if (s.isRtl) "ar" else "en").staffDisabled,
            color = DashboardTokens.inkSoft, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Dimens.s8),
        )
        Spacer(Modifier.height(Dimens.s24))
        ParentButton(s.signIn, { dispatch(SignInContract.Intent.Submit) }, modifier = Modifier.testTag(TestTags.SIGN_IN_SUBMIT), enabled = !state.busy && state.email.isNotBlank() && state.password.isNotBlank())
        Spacer(Modifier.height(Dimens.s24))
    }
}
