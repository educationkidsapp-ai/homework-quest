package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.Phones
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.LocalSchoolBranding
import quest.ui.design.Dimens
import quest.ui.design.Palette

object SettingsContract {
    data class State(
        val loading: Boolean = true, val settings: ParentSettings = ParentSettings("en"), val childName: String = "", val childId: String? = null,
        /** MH3: her own mobile number as it is being typed, and whether the server has the value on the screen. */
        val phone: String = "",
        val phoneKnown: Boolean = false,
        val phoneSaved: Boolean = false,
        /** The shape is wrong — hers to fix, and the only state that marks the field itself. */
        val phoneInvalid: Boolean = false,
        /** The shape was fine and the save did not reach the server. Her number stays in the field to try again. */
        val phoneSaveFailed: Boolean = false,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Language(val code: String) : Intent
        data class Phone(val value: String) : Intent
        data object SavePhone : Intent
    }
    sealed interface Effect : MviEffect
}

class SettingsViewModel(private val parent: ParentRepository, private val children: ChildrenRepository, private val api: ContentApi) : MviViewModel<SettingsContract.State, SettingsContract.Intent, SettingsContract.Effect>(SettingsContract.State()) {
    override suspend fun handle(intent: SettingsContract.Intent) {
        when (intent) {
            SettingsContract.Intent.Load -> {
                val s = parent.settings(); val c = children.currentChild.value
                reduce { copy(loading = false, settings = s, childName = c?.name ?: "", childId = c?.id) }
                // MH1 `GET /parent/me`. A build against a server without the route, or a device offline, simply shows no
                // number rather than an error on a screen whose other four sections are all local.
                val me = runCatching { api.parentProfile() }.getOrNull() ?: return
                reduce { copy(phone = me.phone.orEmpty(), phoneKnown = true) }
            }
            is SettingsContract.Intent.Language -> { parent.setLanguage(intent.code); reduce { copy(settings = settings.copy(language = intent.code)) } }
            is SettingsContract.Intent.Phone ->
                reduce { copy(phone = intent.value, phoneSaved = false, phoneInvalid = false, phoneSaveFailed = false) }
            SettingsContract.Intent.SavePhone -> {
                // Validated with the server's own rule (`Phones`) before the request: she is told what is wrong without
                // a round trip, and what is sent is the normalised number the server would have stored anyway.
                val normalised = Phones.normalise(current.phone)
                if (normalised == null) {
                    reduce { copy(phoneInvalid = true, phoneSaved = false, phoneSaveFailed = false) }
                    return
                }
                // Three outcomes, not two. A number she typed on a bad connection must stay in the field — wiping it
                // and calling it the wrong shape is two lies at once — and only the server refusing it marks it
                // invalid, which after the check above means a rule this mirror does not have.
                val result = runCatching { api.updateParentProfile(normalised) }
                val saved = result.getOrNull()
                val rejected = (result.exceptionOrNull() as? ApiException)?.error?.code == ApiError.BAD_REQUEST
                reduce {
                    copy(
                        phone = saved?.phone.orEmpty().takeIf { saved != null } ?: phone,
                        phoneSaved = saved != null,
                        phoneInvalid = rejected,
                        phoneSaveFailed = saved == null && !rejected,
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsRoute(
    onChangePin: () -> Unit,
    onEditChild: (String) -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
) {
    val vm: SettingsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(SettingsContract.Intent.Load) }
    ParentShell(
        title = { it.settings },
        onBack = onBack,
        currentTab = quest.ui.design.DashboardTab.SETTINGS,
        onTabSelected = { tab ->
            when (tab) {
                quest.ui.design.DashboardTab.HOME -> onHome()
                quest.ui.design.DashboardTab.NOTIFICATION -> onNotifications()
                quest.ui.design.DashboardTab.MESSAGES -> onMessages()
                quest.ui.design.DashboardTab.SETTINGS -> {}
            }
        },
    ) { s -> SettingsScreen(state, s, vm::dispatch, onChangePin, onEditChild) }
}

/** Screens 22–23: language (EN / AR with RTL), child profile, her mobile number (MH3), change PIN, privacy. */
@Composable
fun SettingsScreen(state: SettingsContract.State, s: Strings, dispatch: (SettingsContract.Intent) -> Unit, onChangePin: () -> Unit, onEditChild: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.childProfile)
        ParentButton(state.childName.ifBlank { s.childProfile }, { state.childId?.let(onEditChild) }, primary = false, icon = "🧒", enabled = state.childId != null)
        // §4 `parentPanel.arabic`: with one language there is nothing to choose, so the whole section goes.
        FeatureGate(Flags.PARENT_PANEL_ARABIC) {
            SectionTitle(s.language)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                Chip("English", selected = state.settings.language == "en") { dispatch(SettingsContract.Intent.Language("en")) }
                Chip("العربية", selected = state.settings.language == "ar") { dispatch(SettingsContract.Intent.Language("ar")) }
            }
        }
        // MH3 (owner's item 7): the number the department manager reaches her on, in the Children directory. Hidden
        // until `GET /parent/me` answered — an empty field she cannot save is worse than no field at all.
        if (state.phoneKnown) {
            SectionTitle(s.phone)
            OutlinedTextField(
                state.phone, { dispatch(SettingsContract.Intent.Phone(it)) }, Modifier.fillMaxWidth(),
                placeholder = { Text(s.phoneHint) }, singleLine = true, isError = state.phoneInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            )
            val note = when {
                state.phoneInvalid -> s.phoneInvalid
                state.phoneSaveFailed -> s.phoneSaveFailed
                state.phoneSaved -> s.phoneSaved
                else -> null
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.padding(top = Dimens.s4))
            Spacer(Modifier.height(Dimens.s8))
            ParentButton(s.save, { dispatch(SettingsContract.Intent.SavePhone) }, primary = false, icon = "📞")
        }
        SectionTitle(s.changePin)
        ParentButton(s.changePin, onChangePin, primary = false, icon = "🔒")
        SectionTitle(s.privacy)
        ParentCard { Text(s.privacyBody, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
        // §A: the school's own `appName` where the product's name is shown, falling back to the platform's.
        Text("${LocalSchoolBranding.current.appName} · ${s.version} 0.2.0", style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, modifier = Modifier.padding(vertical = Dimens.s16))
    }
}
