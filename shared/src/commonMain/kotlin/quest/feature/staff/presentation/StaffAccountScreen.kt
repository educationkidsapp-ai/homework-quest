package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import kotlinx.coroutines.CancellationException
import quest.core.platform.BiometricKind
import quest.core.text.isolate
import quest.core.text.ltr
import quest.feature.auth.domain.SessionRoles
import quest.feature.lock.domain.AppLock
import quest.feature.lock.presentation.biometricName
import quest.feature.lock.presentation.lockSetUpHint
import quest.feature.parent.domain.Appearance
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.staff.domain.StaffProfile
import quest.feature.staff.domain.StaffSessionView
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

object StaffAccountContract {
    data class State(
        val profile: StaffProfile? = null,
        val language: String = "en",
        val appearance: Appearance = Appearance.SYSTEM,
        val biometricKind: BiometricKind? = null,
        val lockToSetUp: BiometricKind? = null,
        val biometricOn: Boolean = false,
        /** Feature 19: her own after-hours alerts choice, switched over at once and put back if the save fails. */
        val afterHoursAlerts: Boolean = false,
        /**
         * The last after-hours save was refused. The *screen* words it, in her language; a view model never builds a
         * sentence (`StaffStrings.checkInMessage`'s rule).
         */
        val afterHoursSaveFailed: Boolean = false,
    ) : MviState

    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Language(val code: String) : Intent
        data class SetAppearance(val appearance: Appearance) : Intent
        data class Biometric(val on: Boolean, val reason: String) : Intent
        data object RefreshLock : Intent
        /** Feature 19: `PUT /staff/me/preferences`. */
        data class AfterHoursAlerts(val on: Boolean) : Intent
        data object SignOut : Intent
    }

    sealed interface Effect : MviEffect { data object SignedOut : Effect }
}

/**
 * حسابي / My account: her name, role and number, the language, the Light/Dark/System choice, the app lock and sign
 * out. The language and appearance are the parent side's own keys in `SettingsStore`, so a dual-role account does not
 * have to set the app up twice.
 */
class StaffAccountViewModel(
    private val session: StaffSessionView,
    private val parent: ParentRepository,
    private val lock: AppLock,
    private val roles: SessionRoles,
) : MviViewModel<StaffAccountContract.State, StaffAccountContract.Intent, StaffAccountContract.Effect>(StaffAccountContract.State()) {

    override suspend fun handle(intent: StaffAccountContract.Intent) {
        when (intent) {
            StaffAccountContract.Intent.Load -> {
                val biometricOn = lock.enabled()
                reduce {
                    copy(
                        profile = session.profile.value, language = parent.language.value, appearance = parent.appearance.value,
                        biometricKind = lock.available(), lockToSetUp = lock.toSetUp(), biometricOn = biometricOn,
                        afterHoursAlerts = session.profile.value?.afterHoursAlerts == true,
                    )
                }
            }
            StaffAccountContract.Intent.RefreshLock -> {
                val on = lock.enabled()
                reduce { copy(biometricKind = lock.available(), lockToSetUp = lock.toSetUp(), biometricOn = on) }
            }
            is StaffAccountContract.Intent.Biometric -> {
                val on = lock.setEnabled(intent.on, intent.reason)
                reduce { copy(biometricOn = on) }
            }
            is StaffAccountContract.Intent.AfterHoursAlerts -> {
                // Switched over before the request, because a switch that waits for a round trip feels broken — and
                // back again, with one line to say so, when the server refuses.
                reduce { copy(afterHoursAlerts = intent.on, afterHoursSaveFailed = false) }
                try {
                    session.setAfterHoursAlerts(intent.on)
                    reduce { copy(afterHoursAlerts = session.profile.value?.afterHoursAlerts == true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    reduce { copy(afterHoursAlerts = !intent.on, afterHoursSaveFailed = true) }
                }
            }
            is StaffAccountContract.Intent.Language -> { parent.setLanguage(intent.code); reduce { copy(language = intent.code) } }
            is StaffAccountContract.Intent.SetAppearance -> { parent.setAppearance(intent.appearance); reduce { copy(appearance = intent.appearance) } }
            StaffAccountContract.Intent.SignOut -> { roles.signOut(); effect(StaffAccountContract.Effect.SignedOut) }
        }
    }
}

@Composable
fun StaffAccountRoute(
    onHome: () -> Unit,
    onMessages: () -> Unit,
    onNotifications: () -> Unit,
    onParentSide: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val vm: StaffAccountViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(StaffAccountContract.Intent.Load) }
    LaunchedEffect(vm) { vm.effects.collect { if (it is StaffAccountContract.Effect.SignedOut) onSignedOut() } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.dispatch(StaffAccountContract.Intent.RefreshLock) }
    StaffShell(
        title = { it.account },
        currentTab = StaffTab.ACCOUNT,
        onTabSelected = { tab ->
            when (tab) {
                StaffTab.ATTENDANCE -> onHome()
                StaffTab.MESSAGES -> onMessages()
                StaffTab.NOTIFICATIONS -> onNotifications()
                StaffTab.ACCOUNT -> Unit
            }
        },
        onBack = onHome,
    ) { s, chatStrings ->
        StaffAccountScreen(state, s, chatStrings, vm::dispatch, switch = { SideSwitch(onSwitched = { onParentSide() }) })
    }
}

@Composable
fun StaffAccountScreen(
    state: StaffAccountContract.State,
    strings: StaffStrings,
    chatStrings: Strings,
    dispatch: (StaffAccountContract.Intent) -> Unit,
    /** The Staff/Parent switch, as a slot — see [StaffHomeScreen]. */
    switch: @Composable () -> Unit,
) {
    FeatureGate(Flags.STAFF_MOBILE_APP) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
            Spacer(Modifier.height(Dimens.s8))
            switch()
            val profile = state.profile
            if (profile != null) {
                ParentCard(Modifier.padding(top = Dimens.s8)) {
                    // A worker's name is her generated login (`w.ahmed.4821`), which is a left-to-right value too.
                    Text(isolate(profile.displayName), style = MaterialTheme.typography.titleMedium, color = DashboardTokens.ink)
                    Spacer(Modifier.height(Dimens.s4))
                    Text("${strings.role}: ${strings.roleName(profile.roleWire)}", style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                    profile.phone?.let {
                        Spacer(Modifier.height(Dimens.s4))
                        // `+971…` keeps its plus on the left in the Arabic app: a number is read left to right.
                        Text("${strings.phone}: ${ltr(it)}", style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                    }
                    profile.schoolName?.let {
                        Spacer(Modifier.height(Dimens.s4))
                        Text("${strings.school}: $it", style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                    }
                }
            }

            SectionTitle(strings.language)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                Chip("English", selected = state.language == "en") { dispatch(StaffAccountContract.Intent.Language("en")) }
                Chip("العربية", selected = state.language == "ar") { dispatch(StaffAccountContract.Intent.Language("ar")) }
            }

            SectionTitle(strings.appearance)
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                listOf(
                    Appearance.SYSTEM to strings.appearanceSystem,
                    Appearance.LIGHT to strings.appearanceLight,
                    Appearance.DARK to strings.appearanceDark,
                ).forEach { (value, label) ->
                    DashboardFilterChip(label, selected = state.appearance == value, onClick = { dispatch(StaffAccountContract.Intent.SetAppearance(value)) })
                }
            }

            // Feature 19: only a school that bought office hours has an after-hours question to answer. Her **own**
            // school's flags, from `GET /staff/me`, which is what the profile carries.
            if (profile?.flags?.get(Flags.CHAT_OFFICE_HOURS) == true) {
                SectionTitle(strings.afterHoursSection)
                ParentCard {
                    Text(strings.afterHoursLabel, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.ink)
                    Text(strings.afterHoursHint, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        DashboardFilterChip(
                            chatStrings.biometricOn, selected = state.afterHoursAlerts,
                            onClick = { dispatch(StaffAccountContract.Intent.AfterHoursAlerts(true)) },
                        )
                        DashboardFilterChip(
                            chatStrings.biometricOff, selected = !state.afterHoursAlerts,
                            onClick = { dispatch(StaffAccountContract.Intent.AfterHoursAlerts(false)) },
                        )
                    }
                    if (state.afterHoursSaveFailed) {
                        Spacer(Modifier.height(Dimens.s4))
                        Text(strings.afterHoursSaveFailed, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.error)
                    }
                }
            }

            val shown = state.biometricKind ?: state.lockToSetUp
            if (shown != null) {
                SectionTitle(strings.security)
                ParentCard {
                    val kind = state.biometricKind
                    val title = chatStrings.biometricSetting.replace("{with}", chatStrings.biometricName(shown))
                    Text(title, style = MaterialTheme.typography.titleMedium, color = if (kind == null) DashboardTokens.inkSoft else DashboardTokens.ink)
                    Text(
                        if (kind == null) chatStrings.lockSetUpHint(shown) else chatStrings.biometricSettingHint,
                        style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft,
                    )
                    if (kind != null || state.biometricOn) {
                        Spacer(Modifier.height(Dimens.s8))
                        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                            if (kind != null) {
                                DashboardFilterChip(chatStrings.biometricOn, selected = state.biometricOn, onClick = { dispatch(StaffAccountContract.Intent.Biometric(true, title)) })
                            }
                            DashboardFilterChip(chatStrings.biometricOff, selected = !state.biometricOn, onClick = { dispatch(StaffAccountContract.Intent.Biometric(false, title)) })
                        }
                    }
                }
            }

            Spacer(Modifier.height(Dimens.s16))
            ParentButton(strings.signOut, { dispatch(StaffAccountContract.Intent.SignOut) }, primary = false, icon = "🚪")
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}
