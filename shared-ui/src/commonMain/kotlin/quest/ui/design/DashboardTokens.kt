package quest.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The MySchool brand ramps — the logo's colours, exactly as `docs/brand/palette.md` publishes them and
 * `dashboard/src/styles/_theme.scss` declares them (`--hq-color-brand-*`, `-magenta-*`, `-orange-*`, `-warning-*`).
 * `DashboardThemeDriftTest` reads the SCSS and fails when a step here differs, so the app and the dashboard end on
 * identical values and a change of palette is a change of this object, not of any screen.
 *
 * **Blue leads**: it is the primary and the gradient. Magenta and orange are accents — a badge, a highlight, the
 * stars — and are text only through the ink roles of [DashboardPalette].
 */
object BrandColors {
    // The seven steps `design/tokens.json` carries come through the token pipeline (`DesignTokens`, generated), so a
    // change of palette there reaches the app by regeneration; the steps around them are the published tints and shades.

    // Blue — the diamond. 400 is the logo's light blue and 700 its deep blue: the gradient's two stops.
    val blue25 = Color(0xFFF3FAFD); val blue50 = DesignTokens.colorAccentSoft; val blue100 = Color(0xFFCEEBF9); val blue200 = Color(0xFFA1D9F3)
    val blue300 = Color(0xFF66C2EB); val blue400 = DesignTokens.colorGradientFrom; val blue500 = DesignTokens.colorAccent; val blue600 = DesignTokens.colorAccentStrong
    val blue700 = DesignTokens.colorGradientTo; val blue800 = Color(0xFF064192); val blue900 = Color(0xFF05306E); val blue950 = Color(0xFF052150)

    // Magenta — the upper arcs. 600 is the logo's.
    val magenta50 = Color(0xFFFBF0F7); val magenta100 = Color(0xFFF6DEEE); val magenta200 = Color(0xFFEDBEDC); val magenta300 = Color(0xFFE091C4)
    val magenta400 = Color(0xFFD25EAA); val magenta500 = Color(0xFFC32C8F); val magenta600 = DesignTokens.colorSecondary; val magenta700 = Color(0xFF9C0368)
    val magenta800 = Color(0xFF810356); val magenta900 = Color(0xFF650244)

    // Orange — the lower arcs. 400 is the logo's.
    val orange50 = Color(0xFFFFF6E9); val orange100 = Color(0xFFFEEBCE); val orange200 = Color(0xFFFDD79D); val orange300 = Color(0xFFFCBE60)
    val orange400 = DesignTokens.colorTertiary; val orange500 = Color(0xFFE48B09); val orange600 = Color(0xFFC17307); val orange700 = Color(0xFFA25E05)
    val orange800 = Color(0xFF874B04)

    // Warning — yellow, so that it never reads as the brand's orange.
    val warning50 = Color(0xFFFFFBE0); val warning100 = Color(0xFFFFF5B8); val warning500 = Color(0xFFE6BF00); val warning700 = Color(0xFF7D6500)
}

/**
 * The app's colour roles, one value per role, in a light and a dark set — the dashboard's roles
 * (`dashboard/src/styles/_theme.scss`, `:root` and `html.dark`). Field names follow the `--hq-color-*` role they
 * mirror, and `DashboardThemeDriftTest` fails when one stops matching.
 */
data class DashboardPalette(
    val dark: Boolean,
    // page and surfaces
    val bg: Color,              // --hq-color-bg
    val bgSubtle: Color,        // --hq-color-neutral-soft: a tint one step off the ground
    val surface: Color,         // --hq-color-surface
    val surfaceRaised: Color,   // --hq-color-surface-raised
    // ink
    val ink: Color,             // --hq-color-ink
    val inkStrong: Color,       // titles: gray-900 on light; the same ink on dark, where there is no darker step
    val inkSoft: Color,         // --hq-color-ink-soft
    val inkMuted: Color,        // --hq-color-ink-muted
    val inkLight: Color,        // --hq-color-disabled
    // rules
    val rule: Color,            // --hq-color-rule
    val ruleControl: Color,     // --hq-color-control-rule
    val divider: Color,         // --hq-color-divider
    // brand — primary (blue)
    val brand: Color,           // hq.color.accent / --hq-color-brand-fill: the fill of an action; a school may override it
    val brandStrong: Color,     // hq.color.accent-strong: pressed
    val brandSoft: Color,       // --hq-color-accent-soft, already laid over the surface
    val brandInk: Color,        // --hq-color-accent-ink: the accent as text or a glyph
    val onBrand: Color,
    /** `--hq-gradient-brand`: the diamond's own pair — for marks and glyph tiles, where nothing small is read. */
    val gradient: List<Color>,
    /** `--hq-gradient-brand-fill`: started one step in, so a normal-size white label clears AA across its run. */
    val gradientText: List<Color>,
    // secondary (magenta) and tertiary (orange): the fill, its soft tint over the surface, and the text role
    val secondary: Color, val onSecondary: Color, val secondarySoft: Color, val secondaryInk: Color,
    val tertiary: Color, val onTertiary: Color, val tertiarySoft: Color, val tertiaryInk: Color,
    /** Orange as a *graphic* on the surface — an earned star: at least 3:1 against it (WCAG 1.4.11), which orange-400 on white is not. */
    val tertiaryGraphic: Color,
    // status: the soft tint, the text that goes on it, and the tint's border
    val success: Color, val successBg: Color, val successBorder: Color,
    val warning: Color, val warningBg: Color, val warningBorder: Color,
    val error: Color, val errorBg: Color, val errorBorder: Color,
    val overlay: Color,         // --hq-color-overlay
    /** `--hq-gradient-ground`: the three logo hues at their palest on light, the flat ground on dark. */
    val groundWash: List<Color>,
) {
    companion object {
        // The neutral and status ramps of `_theme.scss`, named as they are there.
        internal val gray50 = Color(0xFFF9FAFB); internal val gray100 = Color(0xFFF2F4F7); internal val gray200 = Color(0xFFE4E7EC)
        internal val gray300 = Color(0xFFD0D5DD); internal val gray400 = Color(0xFF98A2B3); internal val gray500 = Color(0xFF667085)
        internal val gray600 = Color(0xFF475467); internal val gray700 = Color(0xFF344054); internal val gray800 = Color(0xFF1D2939)
        val gray900 = Color(0xFF101828); internal val grayDark = Color(0xFF1A2231)
        internal val success500 = Color(0xFF12B76A); internal val success700 = Color(0xFF027A48)
        internal val error400 = Color(0xFFF97066); internal val error500 = Color(0xFFF04438); internal val error700 = Color(0xFFB42318)
        private val darkSurface = Color(0xFF171F2E)

        /** A translucent tint as the solid colour it makes on [surface] — CSS `color-mix(… N %, transparent)` on a card. */
        private fun Color.on(surface: Color, share: Float) = copy(alpha = share).compositeOver(surface)

        val Light = DashboardPalette(
            dark = false,
            bg = gray50, bgSubtle = gray100, surface = Color.White, surfaceRaised = Color.White,
            ink = gray800, inkStrong = gray900, inkSoft = gray600, inkMuted = gray400, inkLight = gray300,
            rule = gray200, ruleControl = gray300, divider = gray100,
            brand = BrandColors.blue500, brandStrong = BrandColors.blue600, brandSoft = BrandColors.blue50, brandInk = BrandColors.blue600, onBrand = Color.White,
            gradient = listOf(BrandColors.blue400, BrandColors.blue700),
            gradientText = listOf(BrandColors.blue500, BrandColors.blue700),
            secondary = BrandColors.magenta600, onSecondary = Color.White, secondarySoft = BrandColors.magenta50, secondaryInk = BrandColors.magenta700,
            tertiary = BrandColors.orange400, onTertiary = gray900, tertiarySoft = BrandColors.orange50, tertiaryInk = BrandColors.orange800, tertiaryGraphic = BrandColors.orange600,
            success = success700, successBg = Color(0xFFECFDF3), successBorder = Color(0xFFD1FADF),
            warning = BrandColors.warning700, warningBg = BrandColors.warning50, warningBorder = BrandColors.warning100,
            error = error700, errorBg = Color(0xFFFEF3F2), errorBorder = Color(0xFFFEE4E2),
            overlay = gray900.copy(alpha = 0.45f),
            groundWash = listOf(BrandColors.blue50, BrandColors.magenta50, BrandColors.orange50),
        )

        /** `html.dark`: tints are a share of the ramp over the dark card, text on them the step that clears AA. */
        val Dark = DashboardPalette(
            dark = true,
            bg = gray900, bgSubtle = Color.White.copy(alpha = 0.05f), surface = darkSurface, surfaceRaised = grayDark,
            ink = Color.White.copy(alpha = 0.9f), inkStrong = Color.White.copy(alpha = 0.9f), inkSoft = gray400, inkMuted = gray500, inkLight = gray600,
            rule = gray800, ruleControl = gray700, divider = gray800,
            brand = BrandColors.blue500, brandStrong = BrandColors.blue400, brandSoft = BrandColors.blue500.on(darkSurface, 0.16f), brandInk = BrandColors.blue400, onBrand = Color.White,
            gradient = listOf(BrandColors.blue400, BrandColors.blue700),
            gradientText = listOf(BrandColors.blue500, BrandColors.blue700),
            secondary = BrandColors.magenta500, onSecondary = Color.White, secondarySoft = BrandColors.magenta500.on(darkSurface, 0.16f), secondaryInk = BrandColors.magenta300,
            tertiary = BrandColors.orange400, onTertiary = gray900, tertiarySoft = BrandColors.orange400.on(darkSurface, 0.16f), tertiaryInk = BrandColors.orange300, tertiaryGraphic = BrandColors.orange400,
            success = success500, successBg = success500.copy(alpha = 0.15f), successBorder = success500.copy(alpha = 0.3f),
            warning = BrandColors.warning500, warningBg = BrandColors.warning500.copy(alpha = 0.15f), warningBorder = BrandColors.warning500.copy(alpha = 0.3f),
            error = error400, errorBg = error500.copy(alpha = 0.15f), errorBorder = error500.copy(alpha = 0.35f),
            overlay = Color(0xFF0C111D).copy(alpha = 0.7f),
            groundWash = listOf(gray900, gray900, gray900),
        )
    }
}

/** The palette in force; [AcademicTheme] and [ParentTheme] provide the light or the dark one. */
val LocalDashboardPalette = staticCompositionLocalOf { DashboardPalette.Light }

/**
 * The dashboard design system's tokens as a screen reads them. Colours answer for the theme in force (light or dark),
 * so a screen written against `DashboardTokens.ink` needs no dark branch of its own; sizes are the same in both.
 */
object DashboardTokens {
    private val p: DashboardPalette @Composable @ReadOnlyComposable get() = LocalDashboardPalette.current

    val isDark: Boolean @Composable @ReadOnlyComposable get() = p.dark

    val bg: Color @Composable @ReadOnlyComposable get() = p.bg
    val bgSubtle: Color @Composable @ReadOnlyComposable get() = p.bgSubtle
    val surface: Color @Composable @ReadOnlyComposable get() = p.surface
    val surfaceRaised: Color @Composable @ReadOnlyComposable get() = p.surfaceRaised

    val ink: Color @Composable @ReadOnlyComposable get() = p.ink
    val inkStrong: Color @Composable @ReadOnlyComposable get() = p.inkStrong
    val inkSoft: Color @Composable @ReadOnlyComposable get() = p.inkSoft
    val inkMuted: Color @Composable @ReadOnlyComposable get() = p.inkMuted
    val inkLight: Color @Composable @ReadOnlyComposable get() = p.inkLight

    val rule: Color @Composable @ReadOnlyComposable get() = p.rule
    val ruleControl: Color @Composable @ReadOnlyComposable get() = p.ruleControl
    val divider: Color @Composable @ReadOnlyComposable get() = p.divider

    val onBrand: Color @Composable @ReadOnlyComposable get() = p.onBrand

    /**
     * The accent used as *text or a glyph* rather than a fill (`--hq-color-accent-ink`). Unthemed it is the palette's
     * own ink role. A school's accent is itself on a light surface and, on a dark one, mixed 45 % into white, because
     * the same colour on the dark card falls below AA.
     */
    val accentInk: Color
        @Composable @ReadOnlyComposable get() {
            val accent = MaterialTheme.colorScheme.primary
            return when {
                accent == p.brand -> p.brandInk
                p.dark -> lerp(Color.White, accent, 0.45f)
                else -> accent
            }
        }

    /** The drawing pad's paper. White in both palettes: the pens are fixed colours and a stored drawing must look the same. */
    val paper = Color.White

    // Secondary (magenta) and tertiary (orange): accents, never a screen's lead colour.
    val secondary: Color @Composable @ReadOnlyComposable get() = p.secondary
    val onSecondary: Color @Composable @ReadOnlyComposable get() = p.onSecondary
    val secondarySoft: Color @Composable @ReadOnlyComposable get() = p.secondarySoft
    val secondaryInk: Color @Composable @ReadOnlyComposable get() = p.secondaryInk
    val tertiary: Color @Composable @ReadOnlyComposable get() = p.tertiary
    val onTertiary: Color @Composable @ReadOnlyComposable get() = p.onTertiary
    val tertiarySoft: Color @Composable @ReadOnlyComposable get() = p.tertiarySoft
    val tertiaryInk: Color @Composable @ReadOnlyComposable get() = p.tertiaryInk
    val tertiaryGraphic: Color @Composable @ReadOnlyComposable get() = p.tertiaryGraphic

    val success: Color @Composable @ReadOnlyComposable get() = p.success
    val successBg: Color @Composable @ReadOnlyComposable get() = p.successBg
    val successBorder: Color @Composable @ReadOnlyComposable get() = p.successBorder
    val warning: Color @Composable @ReadOnlyComposable get() = p.warning
    val warningBg: Color @Composable @ReadOnlyComposable get() = p.warningBg
    val warningBorder: Color @Composable @ReadOnlyComposable get() = p.warningBorder
    val error: Color @Composable @ReadOnlyComposable get() = p.error
    val errorBg: Color @Composable @ReadOnlyComposable get() = p.errorBg
    val errorBorder: Color @Composable @ReadOnlyComposable get() = p.errorBorder
    val overlay: Color @Composable @ReadOnlyComposable get() = p.overlay

    // ---- Radii (`--hq-radius-*`) ------------------------------------------------------------------------------
    val radiusXs = 4.dp                  // chart bar caps, small tags
    val radiusSm = 8.dp                  // control: buttons, inputs, chips
    val radiusMd = 12.dp                 // tile: icon tiles, inner panels, answer tiles
    val radiusLg = 16.dp                 // card: every card container, sheets
    val radiusFull = 9999.dp             // pill: badges, avatars

    // ---- Sizes --------------------------------------------------------------------------------------------------
    val cardBorderWidth = 1.dp
    val buttonHeight = 44.dp             // --hq-size-control-height

    // ---- Elevation (`--hq-shadow-xs`): gray-900 at 5 % -----------------------------------------------------------
    val shadowSpot = Color(0x0D101828)
    val shadowAmbient = Color(0x05101828)
}
