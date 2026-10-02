// hq-flag: none (the lock stands in front of every screen, including the ones a school's flags decide; it cannot sit behind one)
package quest.feature.lock.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import quest.core.platform.BiometricKind
import quest.feature.lock.domain.AppLock
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.parent.presentation.displayName
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.LocalSchoolBranding
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.DashboardButton
import quest.ui.design.DashboardButtonVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.MySchoolMark
import quest.ui.design.ParentTheme

/** The biometric's name as the copy says it. */
fun Strings.biometricName(kind: BiometricKind?): String = when (kind) {
    BiometricKind.FACE -> biometricFace
    BiometricKind.FINGERPRINT -> biometricTouch
    else -> biometricGeneric
}

/**
 * Wraps the app's screens with the biometric lock (M2). The screens stay composed underneath — the navigation stack,
 * a half-typed message and a deep link's destination are all still there when the cover lifts — and the cover is
 * opaque and takes every touch, so nothing behind it can be read or pressed.
 *
 * The lock is told when the app leaves and returns (`ON_STOP` / `ON_START`): the system's own prompt only pauses the
 * app, so showing it never counts as time away.
 */
@Composable
fun AppLockHost(onSignedOut: () -> Unit, content: @Composable () -> Unit) {
    val lock: AppLock = koinInject()
    val parent: ParentRepository = koinInject()
    val state by lock.state.collectAsState()
    val scope = rememberCoroutineScope()
    val language by parent.language.collectAsStateWithLifecycle()
    val strings = if (featureEnabled(Flags.PARENT_PANEL_ARABIC)) Strings.forLanguage(language) else Strings.en
    val app = LocalSchoolBranding.current.displayName(strings)
    val reason = strings.lockReason.replace("{app}", app)

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { lock.background() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { scope.launch { lock.foreground() } }

    Box(Modifier.fillMaxSize()) {
        content()
        if (state.stage != AppLock.Stage.UNLOCKED) ParentTheme(rtl = strings.isRtl) {
            CompositionLocalProvider(LocalStrings provides strings) {
                when (state.stage) {
                    AppLock.Stage.LOCKED -> {
                        // The prompt comes up with the screen; the two buttons are for when it was dismissed.
                        LaunchedEffect(Unit) { lock.unlock(reason) }
                        LockScreen(state, strings, app, onRetry = { scope.launch { lock.unlock(reason) } }, onPassword = { scope.launch { lock.usePassword(); onSignedOut() } })
                    }
                    AppLock.Stage.OFFER -> BiometricOffer(state.kind, strings, app, onAccept = { scope.launch { lock.acceptOffer(reason) } }, onDecline = { scope.launch { lock.declineOffer() } })
                    AppLock.Stage.UNLOCKED -> Unit
                }
            }
        }
    }
}

/** The cover: the mark, why the app is closed, and — once a prompt was dismissed — the two ways on. */
@Composable
fun LockScreen(state: AppLock.State, s: Strings, app: String, onRetry: () -> Unit, onPassword: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).blockTouches().safeDrawingPadding().padding(Dimens.s24),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        MySchoolMark(size = 88.dp, contentDescription = app)
        Spacer(Modifier.height(Dimens.s24))
        Text(s.lockTitle.replace("{app}", app), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s8))
        Text(
            if (state.kind == null) s.lockBodyNoBiometric else s.lockBody.replace("{with}", s.biometricName(state.kind)),
            style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center,
        )
        if (state.failed) {
            Spacer(Modifier.height(Dimens.s32))
            if (state.kind != null) {
                DashboardButton(s.lockTryAgain, onClick = onRetry, modifier = Modifier.fillMaxWidth(), enabled = !state.prompting)
                Spacer(Modifier.height(Dimens.s12))
            }
            DashboardButton(s.lockUsePassword, onClick = onPassword, modifier = Modifier.fillMaxWidth(), variant = if (state.kind == null) DashboardButtonVariant.PRIMARY else DashboardButtonVariant.SECONDARY)
        }
    }
}

/** The one-time offer after a sign-in: a sheet over the screen she has just arrived on. */
@Composable
fun BiometricOffer(kind: BiometricKind?, s: Strings, app: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    val with = s.biometricName(kind)
    val title = s.biometricOfferTitle.replace("{with}", with)
    Box(Modifier.fillMaxSize().background(DashboardTokens.inkStrong.copy(alpha = 0.35f)).blockTouches(), contentAlignment = Alignment.BottomCenter) {
        val sheet = RoundedCornerShape(topStart = DashboardTokens.radiusLg, topEnd = DashboardTokens.radiusLg)
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, sheet).border(1.dp, MaterialTheme.colorScheme.outline, sheet)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)).padding(Dimens.s24).semantics { contentDescription = title },
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.inkStrong)
            Spacer(Modifier.height(Dimens.s8))
            Text(s.biometricOfferBody.replace("{app}", app).replace("{with}", with), style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink)
            Spacer(Modifier.height(Dimens.s24))
            DashboardButton(s.biometricOfferAccept, onClick = onAccept, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Dimens.s12))
            DashboardButton(s.biometricOfferDecline, onClick = onDecline, modifier = Modifier.fillMaxWidth(), variant = DashboardButtonVariant.SECONDARY)
        }
    }
}

/** Swallows every tap that reaches the cover, so nothing underneath can be pressed through it. */
@Composable
private fun Modifier.blockTouches(): Modifier = clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
