package quest.feature.school.presentation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import quest.feature.children.domain.ChildrenRepository
import quest.feature.school.domain.SchoolBranding
import quest.feature.school.domain.SchoolLogoLoader
import quest.feature.school.domain.SchoolSession
import quest.ui.design.AvatarColors
import quest.ui.design.Motion
import quest.ui.design.Palette
import quest.ui.design.LocalThemeOverrides
import quest.ui.design.ThemeOverrides
import quest.ui.design.WorldPaletteOverrides
import quest.ui.design.schoolThemeOverrides

/** §A: what the app calls itself here — the school's `appName`, else the platform's, else the shipped string. */
val LocalSchoolBranding = staticCompositionLocalOf { SchoolBranding() }

/** How long the app waits between flag/theme syncs (§4: "on launch and every 6 hours"). */
private const val SYNC_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

/**
 * Wraps the whole app: keeps the current child's school in sync and hands every screen below it the school's colours,
 * name, logo and feature flags.
 *
 * The colours cross-fade over [Motion.themeTransitionMillis] (§3's 300 ms), so joining a school in Add child repaints
 * the form the parent is standing in rather than cutting to it. Each role animates from the token value it replaces,
 * which means an unthemed app renders exactly the token colours and a school that overrides three of them animates
 * only those three.
 */
@Composable
fun SchoolThemeHost(content: @Composable () -> Unit) {
    val session: SchoolSession = koinInject()
    val children: ChildrenRepository = koinInject()
    val logos: SchoolLogoLoader = koinInject()

    val child by children.currentChild.collectAsState()
    val theme by session.theme.collectAsState()
    val branding by session.branding.collectAsState()

    // The child decides the school: switching child switches theme and flags together, and a child in the default
    // school clears both. While there is no current child yet, whatever `restore()` painted stays on screen.
    LaunchedEffect(child?.schoolId) { child?.schoolId?.let { runCatching { session.use(it) } } }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { session.sync() }
            delay(SYNC_INTERVAL_MILLIS)
        }
    }

    CompositionLocalProvider(
        LocalThemeOverrides provides animatedOverrides(theme?.let(::schoolThemeOverrides)),
        LocalSchoolBranding provides branding,
        LocalFlags provides session,
        LocalSchoolLogos provides logos,
    ) { content() }
}

/**
 * The app's own look written as a full [ThemeOverrides]: the value each role animates *from*, and where a role a
 * school leaves out lands. Every colour here must be the one its consumer would use with no overrides at all — the
 * host always provides a complete set, so a wrong base here would quietly re-colour the *unthemed* app, which is why
 * `SchoolThemeHostTest` compares the whole scheme against `ThemeOverrides()`.
 */
internal val unthemed = ThemeOverrides(
    primary = Palette.parentSurface,
    primaryInk = Palette.parentInk,
    accent = Palette.parentAccent,
    ground = Palette.parentBg,
    softBorder = Palette.parentLine,
    mascotColor = AvatarColors.body(AvatarColors.MASCOT),
    softAccent = Palette.parentAccentSoft,
    worldPalettes = WorldPaletteOverrides(math = Palette.sand, english = Palette.lavender),
)

/**
 * Every overridable role, animated from the token it replaces. A null [target] (no school, or a theme that leaves a
 * role alone) animates back to [unthemed], so leaving a school fades out of its colours just as joining faded in.
 */
@Composable
private fun animatedOverrides(target: ThemeOverrides?): ThemeOverrides {
    @Composable
    fun role(value: Color?, base: Color?, label: String): Color =
        animateColorAsState(value ?: base ?: Color.Unspecified, tween(Motion.themeTransitionMillis), label = label).value

    return ThemeOverrides(
        primary = role(target?.primary, unthemed.primary, "primary"),
        primaryInk = role(target?.primaryInk, unthemed.primaryInk, "primaryInk"),
        accent = role(target?.accent, unthemed.accent, "accent"),
        ground = role(target?.ground, unthemed.ground, "ground"),
        softBorder = role(target?.softBorder, unthemed.softBorder, "softBorder"),
        mascotColor = role(target?.mascotColor, unthemed.mascotColor, "mascot"),
        softAccent = role(target?.softAccent, unthemed.softAccent, "softAccent"),
        worldPalettes = WorldPaletteOverrides(
            math = role(target?.worldPalettes?.math, unthemed.worldPalettes.math, "worldMath"),
            english = role(target?.worldPalettes?.english, unthemed.worldPalettes.english, "worldEnglish"),
        ),
        fontChoice = target?.fontChoice,
    )
}
