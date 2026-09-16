package quest.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Global switch for looping animations (Pip's bounce, island glow). Off under UI tests — XCUITest waits for the app
 * to become idle before every event, and an endless animation never lets it — and available for a "reduce motion" setting.
 */
object Motion {
    var reduced: Boolean = false

    /** The school-theme colour transition (§3): 300 ms, standard easing. */
    const val themeTransitionMillis = 300
}

/**
 * Per-composition switch for the same looping animations. The screenshot harness sets it to `false` so
 * `rememberInfiniteTransition` never starts: a looping animation reads the host's animation clock, which is wall time
 * on desktop, and two runs of the same screenshot then differ by whatever the clock happened to be.
 *
 * [Motion.reduced] stays the process-wide switch (UI tests set it before the app starts); this one is scoped, so a
 * test can freeze one screen without touching global state.
 */
val LocalAnimationsEnabled = staticCompositionLocalOf { true }

/** True when a looping animation may run here. */
@Composable
fun animationsEnabled(): Boolean = !Motion.reduced && LocalAnimationsEnabled.current
