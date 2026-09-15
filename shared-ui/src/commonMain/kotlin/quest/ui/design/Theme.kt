package quest.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import quest.ui.resources.Res
import quest.ui.resources.archivo_variable
import quest.ui.resources.ibmplexsansarabic_regular
import quest.ui.resources.ibmplexsansarabic_semibold
import quest.ui.resources.nunito_variable

enum class ThemeMode { CHILD, PARENT }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.CHILD }

/**
 * A school's theme (`GET /schools/{id}/theme`) as runtime overrides of the generated [DesignTokens].
 * Field names mirror the theme JSON keys; every colour is nullable and `null` means "keep the token".
 * Nothing client-side validates them — the server rejects a theme whose contrast fails.
 *
 * Wiring the fetch, the 300 ms colour transition and the mascot/world-palette application is P2.2; for now the
 * default [ThemeOverrides] is empty, so [ParentTheme] renders exactly the token values.
 */
data class ThemeOverrides(
    val primary: Color? = null,
    val primaryInk: Color? = null,
    val accent: Color? = null,
    val ground: Color? = null,
    val softBorder: Color? = null,
    val mascotColor: Color? = null,
    val worldPalettes: WorldPaletteOverrides = WorldPaletteOverrides(),
    /** The school's font family key; `null` keeps Archivo / IBM Plex Sans Arabic. */
    val fontChoice: String? = null,
) {
    /** True when the school theme carries nothing — the default, and the only state phase 1 ever sees. */
    val isEmpty: Boolean
        get() = primary == null && primaryInk == null && accent == null && ground == null &&
            softBorder == null && mascotColor == null && worldPalettes.isEmpty && fontChoice == null
}

/** `theme.worldPalettes.math` / `.english` — the per-subject world colour a school may override. */
data class WorldPaletteOverrides(val math: Color? = null, val english: Color? = null) {
    val isEmpty: Boolean get() = math == null && english == null
}

/** No overrides by default; P2.2 provides the school's theme around the app's root. */
val LocalThemeOverrides = compositionLocalOf { ThemeOverrides() }

@Composable
fun childFontFamily(): FontFamily = FontFamily(
    Font(Res.font.nunito_variable, FontWeight.Normal),
    Font(Res.font.nunito_variable, FontWeight.SemiBold),
    Font(Res.font.nunito_variable, FontWeight.Bold),
)

/** Parent mode / admin: Archivo (LTR); IBM Plex Sans Arabic for RTL. */
@Composable
fun parentFontFamily(rtl: Boolean): FontFamily = if (rtl) FontFamily(
    Font(Res.font.ibmplexsansarabic_regular, FontWeight.Normal),
    Font(Res.font.ibmplexsansarabic_semibold, FontWeight.SemiBold),
) else FontFamily(
    Font(Res.font.archivo_variable, FontWeight.Normal),
    Font(Res.font.archivo_variable, FontWeight.SemiBold),
    Font(Res.font.archivo_variable, FontWeight.Bold),
)

/** Child type scale (docs/design.md §3). */
object ChildType {
    fun display(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold)
    fun title(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    fun body(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
    fun label(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    fun tile(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
}

private fun childScheme(): ColorScheme = lightColorScheme(
    primary = Palette.sun,
    onPrimary = Palette.ink,
    secondary = Palette.lavender,
    onSecondary = Palette.ink,
    tertiary = Palette.mint,
    background = Palette.sky,
    onBackground = Palette.ink,
    surface = Palette.cream,
    onSurface = Palette.ink,
    surfaceVariant = Palette.peach,
    onSurfaceVariant = Palette.ink,
    outline = Palette.inkSoft,
    error = Palette.coral,
    onError = Palette.ink,
)

/**
 * The parent/admin scheme. Colours come from [Palette] (generated from `design/tokens.json`); a school theme may
 * replace individual roles through [overrides] — with the default, empty [ThemeOverrides] the result is identical
 * to the token values.
 */
internal fun parentScheme(overrides: ThemeOverrides = ThemeOverrides()): ColorScheme = lightColorScheme(
    primary = overrides.primary ?: Palette.parentAccent,
    onPrimary = overrides.primaryInk ?: Palette.white,
    primaryContainer = Palette.parentAccentSoft,
    onPrimaryContainer = Palette.parentInk,
    secondary = overrides.accent ?: Palette.lavender,
    background = overrides.ground ?: Palette.parentBg,
    onBackground = Palette.parentInk,
    surface = Palette.parentSurface,
    onSurface = Palette.parentInk,
    surfaceVariant = overrides.ground ?: Palette.parentBg,
    onSurfaceVariant = Palette.parentInkSoft,
    outline = overrides.softBorder ?: Palette.parentLine,
    error = Palette.coral,
)

@Composable
fun ChildTheme(content: @Composable () -> Unit) {
    val family = childFontFamily()
    val typography = Typography(
        displayLarge = ChildType.display(family),
        headlineMedium = ChildType.title(family),
        titleLarge = ChildType.title(family),
        bodyLarge = ChildType.body(family),
        labelLarge = ChildType.label(family),
        bodyMedium = ChildType.body(family),
    )
    CompositionLocalProvider(LocalThemeMode provides ThemeMode.CHILD, LocalLayoutDirection provides LayoutDirection.Ltr) {
        MaterialTheme(colorScheme = childScheme(), typography = typography) {
            Box(Modifier.fillMaxSize().background(Palette.sky)) { content() }
        }
    }
}

/**
 * Admin-panel text styles (web): 30px titles, 17px inputs, 12px uppercase section labels at 0.14em.
 * The title and input steps come from `design/tokens.json` (`hq.font.title-*`, `hq.font.input-*`, `hq.font.body-line`);
 * the steps the dashboard has no token for (description, nav item, card title, the 12px section label, mono) stay here.
 */
object AdminType {
    fun title(f: FontFamily) = TextStyle(
        fontFamily = f,
        fontSize = DesignTokens.fontTitleSize,
        lineHeight = DesignTokens.fontTitleLine,
        fontWeight = FontWeight(DesignTokens.fontTitleWeight),
    )
    fun description(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 22.sp)
    fun input(f: FontFamily) = TextStyle(
        fontFamily = f,
        fontSize = DesignTokens.fontInputSize,
        lineHeight = DesignTokens.fontBodyLine,
        fontWeight = FontWeight(DesignTokens.fontInputWeight),
    )
    fun label(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.14.em)
    fun navItem(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    fun cardTitle(f: FontFamily) = TextStyle(fontFamily = f, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
    fun mono(f: FontFamily) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
}

/**
 * The parent-mode Material type scale. `titleMedium` is the token body step (`hq.font.body-size` / `body-line`) at
 * SemiBold; the other steps have no dashboard counterpart yet and stay literal until `design/tokens.json` grows one.
 * Pulled out of [ParentTheme] so `TokensDriftTest` can assert it without a composition.
 */
internal fun parentTypography(family: FontFamily) = Typography(
    headlineMedium = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = family, fontSize = DesignTokens.fontBodySize, lineHeight = DesignTokens.fontBodyLine, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodySmall = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp),
)

@Composable
fun ParentTheme(rtl: Boolean, content: @Composable () -> Unit) {
    val family = parentFontFamily(rtl)
    val scheme = parentScheme(LocalThemeOverrides.current)
    CompositionLocalProvider(
        LocalThemeMode provides ThemeMode.PARENT,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = scheme, typography = parentTypography(family), shapes = Shapes(extraSmall = RoundedCornerShape(0), small = RoundedCornerShape(0), medium = RoundedCornerShape(0), large = RoundedCornerShape(0), extraLarge = RoundedCornerShape(0))) {
            Box(Modifier.fillMaxSize().background(scheme.background)) { content() }
        }
    }
}
