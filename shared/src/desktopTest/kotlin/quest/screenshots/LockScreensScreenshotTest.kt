package quest.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Subject
import quest.core.platform.BiometricKind
import quest.feature.lock.domain.AppLock
import quest.feature.lock.presentation.BiometricOffer
import quest.feature.lock.presentation.LockScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.parent.presentation.Strings
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/** M2: the lock screen (as it appears, and after a dismissed prompt) and the one-time offer, light and dark, EN and AR. */
class LockScreensScreenshotTest {
    private fun shot(name: String, strings: Strings = Strings.en, dark: Boolean = false, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) { CompositionLocalProvider(LocalDarkTheme provides dark) { ParentTheme(rtl = strings.isRtl) { content(strings) } } }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private val locked = AppLock.State(AppLock.Stage.LOCKED, BiometricKind.FACE)
    private val dismissed = locked.copy(failed = true)

    @Test fun lockScreen() = shot("50-lock") { LockScreen(locked, it, it.appName, {}, {}) }
    @Test fun lockScreenDismissed() = shot("50b-lock-dismissed") { LockScreen(dismissed, it, it.appName, {}, {}) }
    @Test fun lockScreenDismissedDark() = shot("50c-lock-dismissed-dark", dark = true) { LockScreen(dismissed, it, it.appName, {}, {}) }
    @Test fun lockScreenDismissedArabic() = shot("50d-lock-dismissed-ar", strings = Strings.ar) { LockScreen(dismissed, it, it.appName, {}, {}) }
    /** Face ID locked out, switched off or removed: still locked, opened with the device passcode. */
    @Test fun lockScreenPasscodeOnly() = shot("50e-lock-passcode") { LockScreen(dismissed.copy(kind = null), it, it.appName, {}, {}) }
    @Test fun privacyCover() = shot("50f-privacy-cover", dark = true) { quest.feature.lock.presentation.PrivacyCover(it.appName) }

    /** The offer sits over the screen she has just arrived on. */
    @Composable private fun Offer(s: Strings, kind: BiometricKind) = Box(Modifier.fillMaxSize()) {
        WorldMapScreen(MapContract.State(loading = false, child = Child("c", "Maya", "sun", Curriculum.BRITISH, 1), islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 10, 2), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
        )), {}, {}, strings = s)
        BiometricOffer(kind, s, s.appName, {}, {})
    }

    @Test fun offer() = shot("51-biometric-offer") { Offer(it, BiometricKind.FACE) }
    @Test fun offerDark() = shot("51b-biometric-offer-dark", dark = true) { Offer(it, BiometricKind.GENERIC) }
    @Test fun offerArabic() = shot("51c-biometric-offer-ar", strings = Strings.ar) { Offer(it, BiometricKind.FACE) }
    /** M6: an Android phone whose face unlock apps cannot use — the offer names its screen lock. */
    @Test fun offerScreenLock() = shot("51d-screen-lock-offer") { Offer(it, BiometricKind.SCREEN_LOCK) }
    @Test fun offerScreenLockArabic() = shot("51e-screen-lock-offer-ar", strings = Strings.ar) { Offer(it, BiometricKind.SCREEN_LOCK) }
}
