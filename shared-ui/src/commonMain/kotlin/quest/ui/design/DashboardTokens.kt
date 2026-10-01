package quest.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/**
 * The MySchool brand colours, sampled from the logo (`docs/brand/myschool-logo-source.jpg`) and built out into ramps.
 * The 500 step of each hue — and blue's 700 — are the logo's own values; the other steps are tints and shades of
 * them. **Blue leads**: it is the primary and the gradient. Magenta and orange are accents — a badge, a highlight,
 * the stars — and are never body text on a light surface except through their 700 step, which clears AA.
 */
object BrandColors {
    // Blue — the diamond. Its gradient runs from 500 (top-left) to 700 (right).
    val blue50 = Color(0xFFE8F5FD); val blue100 = Color(0xFFCDE9FA); val blue200 = Color(0xFF9DD3F5); val blue300 = Color(0xFF5FB9EE)
    val blue400 = Color(0xFF2AA5E6); val blue500 = Color(0xFF0C9DE2); val blue600 = Color(0xFF0A72C7); val blue700 = Color(0xFF0B55B5)
    val blue800 = Color(0xFF0A438F); val blue900 = Color(0xFF0A3470)

    // Magenta — the upper arcs.
    val magenta50 = Color(0xFFFCE8F4); val magenta100 = Color(0xFFF8CCE6); val magenta200 = Color(0xFFF09ACF); val magenta300 = Color(0xFFE56BB7)
    val magenta400 = Color(0xFFD93A9E); val magenta500 = Color(0xFFB8037A); val magenta600 = Color(0xFF9A0267); val magenta700 = Color(0xFF7C0253)

    // Orange — the lower arcs.
    val orange50 = Color(0xFFFFF5E1); val orange100 = Color(0xFFFEE7B8); val orange300 = Color(0xFFFCC25A); val orange400 = Color(0xFFFBAE2B)
    val orange500 = Color(0xFFF99C0A); val orange600 = Color(0xFFC97A00); val orange700 = Color(0xFF8F5600)
}

/**
 * The app's colour roles, one value per role, in a light and a dark set.
 *
 * **Neutrals and status colours are the dashboard's** (`dashboard/src/styles/_theme.scss`): field names follow the
 * `--hq-color-*` role they mirror and `DashboardThemeDriftTest` reads the SCSS and fails when one stops matching.
 * **Brand colours are the logo's** ([BrandColors]) — the dashboard still wears its own blue-violet, and may adopt
 * these later; until then the brand roles are asserted for contrast, not for parity.
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
    val brand: Color,           // the fill of an action; carries [onBrand] at AA
    val brandStrong: Color,     // pressed, and link text
    val brandInk: Color,        // the brand as text or a glyph on a surface
    val onBrand: Color,
    /** The diamond's gradient, exactly as the logo has it — for marks and glyph tiles, where nothing small is read. */
    val gradient: List<Color>,
    /** The same gradient started one step deeper, so a normal-size white label clears AA across its whole run. */
    val gradientText: List<Color>,
    // secondary (magenta): the fill with [onSecondary], the soft tint, and the text that goes on the tint or a surface
    val secondary: Color, val onSecondary: Color, val secondarySoft: Color, val secondaryInk: Color,
    // tertiary (orange): as above; the fill is light, so what sits on it is dark
    val tertiary: Color, val onTertiary: Color, val tertiarySoft: Color, val tertiaryInk: Color,
    // status: the soft tint, the text that goes on it, and the tint's border
    val success: Color, val successBg: Color, val successBorder: Color,
    val warning: Color, val warningBg: Color, val warningBorder: Color,
    val error: Color, val errorBg: Color, val errorBorder: Color,
    val overlay: Color,         // --hq-color-overlay
    /** The shell's page wash (`shell.component.ts`): three soft stops at 135° on light, the flat ground on dark. */
    val groundWash: List<Color>,
) {
    companion object {
        // The ramps of `_theme.scss`, named as they are there.
        internal val gray50 = Color(0xFFF9FAFB); internal val gray100 = Color(0xFFF2F4F7); internal val gray200 = Color(0xFFE4E7EC)
        internal val gray300 = Color(0xFFD0D5DD); internal val gray400 = Color(0xFF98A2B3); internal val gray500 = Color(0xFF667085)
        internal val gray600 = Color(0xFF475467); internal val gray700 = Color(0xFF344054); internal val gray800 = Color(0xFF1D2939)
        internal val gray900 = Color(0xFF101828); internal val grayDark = Color(0xFF1A2231)
        internal val success500 = Color(0xFF12B76A); internal val success700 = Color(0xFF027A48)
        internal val warning500 = Color(0xFFF79009); internal val warning700 = Color(0xFFB54708)
        internal val error400 = Color(0xFFF97066); internal val error500 = Color(0xFFF04438); internal val error700 = Color(0xFFB42318)

        val Light = DashboardPalette(
            dark = false,
            bg = gray50, bgSubtle = gray100, surface = Color.White, surfaceRaised = Color.White,
            ink = gray800, inkStrong = gray900, inkSoft = gray600, inkMuted = gray400, inkLight = gray300,
            rule = gray200, ruleControl = gray300, divider = gray100,
            brand = BrandColors.blue700, brandStrong = BrandColors.blue800, brandInk = BrandColors.blue700, onBrand = Color.White,
            gradient = listOf(BrandColors.blue500, BrandColors.blue700),
            gradientText = listOf(BrandColors.blue600, BrandColors.blue700),
            secondary = BrandColors.magenta500, onSecondary = Color.White, secondarySoft = BrandColors.magenta50, secondaryInk = BrandColors.magenta700,
            tertiary = BrandColors.orange500, onTertiary = gray900, tertiarySoft = BrandColors.orange50, tertiaryInk = BrandColors.orange700,
            success = success700, successBg = Color(0xFFECFDF3), successBorder = Color(0xFFD1FADF),
            warning = warning700, warningBg = Color(0xFFFFFAEB), warningBorder = Color(0xFFFEF0C7),
            error = error700, errorBg = Color(0xFFFEF3F2), errorBorder = Color(0xFFFEE4E2),
            overlay = gray900.copy(alpha = 0.45f),
            // The dashboard shell's three-stop wash, in the logo's hues: a breath of blue, the ground, a breath of magenta.
            groundWash = listOf(Color(0xFFEEF7FD), gray50, Color(0xFFFDF3F9)),
        )

        /** `html.dark`: status tints are the ramp's 500 at 15 %, text on them the lighter step that clears AA. */
        val Dark = DashboardPalette(
            dark = true,
            bg = gray900, bgSubtle = Color.White.copy(alpha = 0.05f), surface = Color(0xFF171F2E), surfaceRaised = grayDark,
            ink = Color.White.copy(alpha = 0.9f), inkStrong = Color.White.copy(alpha = 0.9f), inkSoft = gray400, inkMuted = gray500, inkLight = gray600,
            rule = gray800, ruleControl = gray700, divider = gray800,
            brand = BrandColors.blue600, brandStrong = BrandColors.blue500, brandInk = BrandColors.blue300, onBrand = Color.White,
            gradient = listOf(BrandColors.blue500, BrandColors.blue700),
            gradientText = listOf(BrandColors.blue600, BrandColors.blue700),
            secondary = BrandColors.magenta500, onSecondary = Color.White, secondarySoft = BrandColors.magenta500.copy(alpha = 0.18f), secondaryInk = BrandColors.magenta200,
            tertiary = BrandColors.orange500, onTertiary = gray900, tertiarySoft = BrandColors.orange500.copy(alpha = 0.15f), tertiaryInk = BrandColors.orange400,
            success = success500, successBg = success500.copy(alpha = 0.15f), successBorder = success500.copy(alpha = 0.3f),
            warning = warning500, warningBg = warning500.copy(alpha = 0.15f), warningBorder = warning500.copy(alpha = 0.3f),
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
     * The accent used as *text or a glyph* rather than a fill (the dashboard's `--hq-color-accent-ink`). Unthemed it is
     * the palette's brand ink. A school's own accent is itself on a light surface and, on a dark one, mixed 45 % into
     * white — the dashboard's rule — because the same colour on `#171f2e` falls below AA.
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

    // Secondary (magenta) and tertiary (orange): accents, never a screen's lead colour. A school with its own theme
    // has one accent, so under a school theme both collapse onto it (see `effectivePalette`).
    val secondary: Color @Composable @ReadOnlyComposable get() = p.secondary
    val onSecondary: Color @Composable @ReadOnlyComposable get() = p.onSecondary
    val secondarySoft: Color @Composable @ReadOnlyComposable get() = p.secondarySoft
    val secondaryInk: Color @Composable @ReadOnlyComposable get() = p.secondaryInk
    val tertiary: Color @Composable @ReadOnlyComposable get() = p.tertiary
    val onTertiary: Color @Composable @ReadOnlyComposable get() = p.onTertiary
    val tertiarySoft: Color @Composable @ReadOnlyComposable get() = p.tertiarySoft
    val tertiaryInk: Color @Composable @ReadOnlyComposable get() = p.tertiaryInk

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
