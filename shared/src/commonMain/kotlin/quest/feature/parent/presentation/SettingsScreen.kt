package quest.feature.parent.presentation

import quest.feature.lock.presentation.biometricName
import quest.feature.lock.presentation.lockSetUpHint
import quest.feature.lock.domain.AppLock
import quest.core.platform.BiometricKind
import quest.ui.design.DashboardFilterChip
import quest.feature.parent.domain.Appearance
import quest.ui.design.DashboardTokens
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.Phones
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.LocalSchoolBranding
import quest.ui.design.Dimens

object SettingsContract {
    data class State(
        val loading: Boolean = true, val settings: ParentSettings = ParentSettings("en"), val appearance: Appearance = Appearance.SYSTEM,
        /** MH3: her own mobile number as it is being typed, and whether the server has the value on the screen. */
        val phone: String = "",
        val phoneKnown: Boolean = false,
        val phoneSaved: Boolean = false,
        /** The shape is wrong — hers to fix, and the only state that marks the field itself. */
        val phoneInvalid: Boolean = false,
        /** The shape was fine and the save did not reach the server. Her number stays in the field to try again. */
        val phoneSaveFailed: Boolean = false,
        /**
         * M2: what this device unlocks with and whether this account unlocks with it. Null (M6) is a device with nothing
         * to prompt with: the row then says what to set up ([lockToSetUp]), or is hidden where there is nothing to set
         * up (desktop).
         */
        val biometricKind: BiometricKind? = null,
        val lockToSetUp: BiometricKind? = null,
        val biometricOn: Boolean = false,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Language(val code: String) : Intent
        data class SetAppearance(val appearance: Appearance) : Intent
        data class Phone(val value: String) : Intent
        data object SavePhone : Intent
        /** [reason] is the line the system prompt shows; turning the lock on needs that prompt to succeed. */
        data class Biometric(val on: Boolean, val reason: String) : Intent
        /** M6: back on the screen — she may have just set a screen lock (or enrolled a fingerprint) in the phone's settings. */
        data object RefreshLock : Intent
    }
    sealed interface Effect : MviEffect
}

class SettingsViewModel(private val parent: ParentRepository, private val api: ContentApi, private val lock: AppLock) : MviViewModel<SettingsContract.State, SettingsContract.Intent, SettingsContract.Effect>(SettingsContract.State()) {
    override suspend fun handle(intent: SettingsContract.Intent) {
        when (intent) {
            SettingsContract.Intent.Load -> {
                val s = parent.settings()
                val biometricOn = lock.enabled()
                reduce { copy(loading = false, settings = s, appearance = parent.appearance.value, biometricKind = lock.available(), lockToSetUp = lock.toSetUp(), biometricOn = biometricOn) }
                // MH1 `GET /parent/me`. A build against a server without the route, or a device offline, simply shows no
                // number rather than an error on a screen whose other four sections are all local.
                val me = runCatching { api.parentProfile() }.getOrNull() ?: return
                reduce { copy(phone = me.phone.orEmpty(), phoneKnown = true) }
            }
            is SettingsContract.Intent.Biometric -> { val on = lock.setEnabled(intent.on, intent.reason); reduce { copy(biometricOn = on) } }
            SettingsContract.Intent.RefreshLock -> { val on = lock.enabled(); reduce { copy(biometricKind = lock.available(), lockToSetUp = lock.toSetUp(), biometricOn = on) } }
            is SettingsContract.Intent.SetAppearance -> { parent.setAppearance(intent.appearance); reduce { copy(appearance = intent.appearance) } }
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
    onBack: () -> Unit,
    onHome: () -> Unit = onBack,
    onNotifications: () -> Unit = {},
    onMessages: () -> Unit = {},
    onComplaints: () -> Unit = {},
) {
    val vm: SettingsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(SettingsContract.Intent.Load) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.dispatch(SettingsContract.Intent.RefreshLock) }
    ParentShell(
        title = { it.settings },
        onBack = onBack,
        currentTab = quest.ui.design.DashboardTab.SETTINGS,
        onTabSelected = { tab ->
            when (tab) {
                quest.ui.design.DashboardTab.HOME -> onHome()
                quest.ui.design.DashboardTab.NOTIFICATION -> onNotifications()
                quest.ui.design.DashboardTab.MESSAGES -> onMessages()
                quest.ui.design.DashboardTab.COMPLAINTS -> onComplaints()
                quest.ui.design.DashboardTab.SETTINGS -> {}
            }
        },
    ) { s -> SettingsScreen(state, s, vm::dispatch, onChangePin) }
}

/** Screens 22–23: language (EN / AR with RTL), her mobile number (MH3), change PIN, privacy. The child's profile is the school's to edit. */
@Composable
fun SettingsScreen(state: SettingsContract.State, s: Strings, dispatch: (SettingsContract.Intent) -> Unit, onChangePin: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        // §4 `parentPanel.arabic`: with one language there is nothing to choose, so the whole section goes.
        FeatureGate(Flags.PARENT_PANEL_ARABIC) {
            SectionTitle(s.language)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                Chip("English", selected = state.settings.language == "en") { dispatch(SettingsContract.Intent.Language("en")) }
                Chip("العربية", selected = state.settings.language == "ar") { dispatch(SettingsContract.Intent.Language("ar")) }
            }
        }
        // Light / Dark / System. System is the default and follows the device as it changes.
        SectionTitle(s.appearance)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            listOf(Appearance.SYSTEM to s.appearanceSystem, Appearance.LIGHT to s.appearanceLight, Appearance.DARK to s.appearanceDark).forEach { (value, label) ->
                DashboardFilterChip(label, selected = state.appearance == value, onClick = { dispatch(SettingsContract.Intent.SetAppearance(value)) })
            }
        }
        // M2: unlock with Face ID / fingerprint — or (M6) the phone's screen lock / the iPhone's passcode. Always there
        // for a signed-in parent on a phone: a device with nothing to prompt with says, in its platform's words, what
        // to set up instead of offering a switch that cannot work. Hidden only where no prompt exists (desktop). On
        // asks for the prompt before it counts; Off stays reachable for a lock that is on, whatever the device says now.
        val shown = state.biometricKind ?: state.lockToSetUp
        if (shown != null) {
            SectionTitle(s.security)
            ParentCard {
                val kind = state.biometricKind
                val title = s.biometricSetting.replace("{with}", s.biometricName(shown))
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (kind == null) DashboardTokens.inkSoft else DashboardTokens.ink)
                Text(if (kind == null) s.lockSetUpHint(shown) else s.biometricSettingHint, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                if (kind != null || state.biometricOn) {
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        if (kind != null) DashboardFilterChip(s.biometricOn, selected = state.biometricOn, onClick = { dispatch(SettingsContract.Intent.Biometric(true, title)) })
                        DashboardFilterChip(s.biometricOff, selected = !state.biometricOn, onClick = { dispatch(SettingsContract.Intent.Biometric(false, title)) })
                    }
                }
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
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(top = Dimens.s4))
            Spacer(Modifier.height(Dimens.s8))
            ParentButton(s.save, { dispatch(SettingsContract.Intent.SavePhone) }, primary = false, icon = "📞")
        }
        SectionTitle(s.changePin)
        ParentButton(s.changePin, onChangePin, primary = false, icon = "🔒")
        SectionTitle(s.privacy)
        ParentCard { Text(s.privacyBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft) }
        // §A: the school's own `appName` where the product's name is shown, falling back to the platform's.
        Text("${LocalSchoolBranding.current.displayName(s)} · ${s.version} 0.2.0", style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, modifier = Modifier.padding(vertical = Dimens.s16))
    }
}
