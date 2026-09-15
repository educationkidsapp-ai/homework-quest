package quest.admin.feature.auth.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import quest.admin.core.design.AdminButton
import quest.admin.core.design.ButtonKind
import quest.admin.core.design.Card
import quest.admin.core.design.ErrorBanner
import quest.admin.core.design.Field
import quest.admin.core.design.Gap
import quest.admin.core.design.titleStyle
import quest.ui.design.AdminTokens

import quest.admin.core.mvi.MviEffect
import quest.admin.core.mvi.MviIntent
import quest.admin.core.mvi.MviState
import quest.admin.core.mvi.MviViewModel
import quest.admin.feature.auth.domain.SessionStore
import quest.api.AdminApi
import quest.api.ApiException
import quest.ui.design.Palette

object SignInContract {
    data class State(val email: String = "", val password: String = "", val busy: Boolean = false, val error: String? = null) : MviState
    sealed interface Intent : MviIntent { data class Email(val v: String) : Intent; data class Password(val v: String) : Intent; data object Submit : Intent }
    sealed interface Effect : MviEffect { data object SignedIn : Effect }
}

class SignInViewModel(private val api: AdminApi, private val session: SessionStore) : MviViewModel<SignInContract.State, SignInContract.Intent, SignInContract.Effect>(SignInContract.State()) {
    override suspend fun handle(intent: SignInContract.Intent) = when (intent) {
        is SignInContract.Intent.Email -> reduce { copy(email = intent.v, error = null) }
        is SignInContract.Intent.Password -> reduce { copy(password = intent.v, error = null) }
        SignInContract.Intent.Submit -> {
            reduce { copy(busy = true, error = null) }
            try { session.save(api.signIn(current.email.trim(), current.password)); reduce { copy(busy = false) }; effect(SignInContract.Effect.SignedIn) }
            catch (e: ApiException) { reduce { copy(busy = false, error = e.error.message) } }
            catch (e: Exception) { reduce { copy(busy = false, error = "Can't reach the server.") } }
        }
    }
}

@Composable
fun SignInScreen(vm: SignInViewModel, apiBaseUrl: String) {
    val s by vm.state.collectAsState()
    Box(Modifier.fillMaxSize().background(Palette.parentBg), contentAlignment = Alignment.Center) {
        Card(Modifier.width(AdminTokens.phoneWidth + AdminTokens.gutter)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AdminTokens.gutter / 2)) {
                Box(Modifier.size(AdminTokens.logoSize).background(Palette.parentAccent))
                Text("Homework Quest", style = titleStyle(), color = Palette.parentInk)
            }
            Gap()
            Text("Admin sign in · ${apiBaseUrl.removePrefix("https://")}", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            Gap(2)
            ErrorBanner(s.error)
            Field(s.email, { vm.dispatch(SignInContract.Intent.Email(it)) }, "Email", placeholder = "admin@quest.local")
            Gap(2)
            Field(s.password, { vm.dispatch(SignInContract.Intent.Password(it)) }, "Password", password = true, onSubmit = { if (s.email.isNotBlank() && s.password.isNotBlank()) vm.dispatch(SignInContract.Intent.Submit) })
            Gap(3)
            AdminButton(if (s.busy) "Signing in…" else "Sign in", { vm.dispatch(SignInContract.Intent.Submit) }, Modifier.fillMaxWidth(), enabled = !s.busy && s.email.isNotBlank() && s.password.isNotBlank(), kind = ButtonKind.ACCENT)
        }
    }
}
