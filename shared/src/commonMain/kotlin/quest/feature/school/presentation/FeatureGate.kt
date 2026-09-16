package quest.feature.school.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import quest.api.DEFAULT_FLAGS
import quest.feature.school.domain.FlagStore

/**
 * §4's rule on the app side: **every screen sits inside a gate.** A screen whose flag is off is simply not composed —
 * no placeholder, no message, no error — so a school that has not bought a feature never learns it exists.
 *
 *     FeatureGate("stickers.treasureChest") { TreasureChestScreen(…) }
 *
 * `./gradlew :shared:checkFeatureGates` fails when a new `*Screen.kt` has neither a `FeatureGate(` reference nor a
 * `// hq-flag: none (<reason>)` line.
 *
 * The flags come from a composition local rather than from Koin so a screenshot test, a preview or the admin phone
 * preview can render a screen without a container; the default answers `DEFAULT_FLAGS`, which is what a school with no
 * row of its own sees.
 */
@Composable
fun FeatureGate(key: String, content: @Composable () -> Unit) {
    if (featureEnabled(key)) content()
}

/**
 * What to do when a *route* is gated off: its content is not composed, so without this the destination would be a
 * blank screen. Entry points are hidden by their own gate, so this only ever fires for a deep link or a flag flipped
 * while the screen was open — and then it quietly goes back rather than showing an error.
 */
@Composable
fun GateFallback(key: String, onClosed: () -> Unit) {
    val open = featureEnabled(key)
    LaunchedEffect(open) { if (!open) onClosed() }
}

/** The same decision as [FeatureGate] where the flag changes a parameter rather than whether something is drawn. */
@Composable
fun featureEnabled(key: String): Boolean {
    val flags by LocalFlags.current.flags.collectAsState()
    return flags[key] ?: DEFAULT_FLAGS[key] ?: false
}

/** Provided by the app root from the [quest.feature.school.domain.SchoolSession]; [PlatformDefaultFlags] elsewhere. */
val LocalFlags = staticCompositionLocalOf<FlagStore> { PlatformDefaultFlags }

/** Every flag at the value `V5__flags_themes.sql` seeds it with: what a school with no row of its own sees. */
object PlatformDefaultFlags : FlagStore {
    private val state = MutableStateFlow(DEFAULT_FLAGS)
    override val flags: StateFlow<Map<String, Boolean>> = state.asStateFlow()
}
