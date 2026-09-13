package quest.core.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import quest.resources.Res
import quest.resources.ibmplexsans_regular
import quest.resources.ibmplexsans_semibold
import quest.resources.ibmplexsansarabic_regular
import quest.resources.ibmplexsansarabic_semibold
import quest.resources.nunito_variable

enum class ThemeMode { CHILD, PARENT }

val LocalThemeMode = staticCompositionLocalOf { ThemeMode.CHILD }

@Composable
fun childFontFamily(): FontFamily = FontFamily(
    Font(Res.font.nunito_variable, FontWeight.Normal),
    Font(Res.font.nunito_variable, FontWeight.SemiBold),
    Font(Res.font.nunito_variable, FontWeight.Bold),
)

@Composable
fun parentFontFamily(rtl: Boolean): FontFamily = if (rtl) FontFamily(
    Font(Res.font.ibmplexsansarabic_regular, FontWeight.Normal),
    Font(Res.font.ibmplexsansarabic_semibold, FontWeight.SemiBold),
) else FontFamily(
    Font(Res.font.ibmplexsans_regular, FontWeight.Normal),
    Font(Res.font.ibmplexsans_semibold, FontWeight.SemiBold),
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

private fun parentScheme(): ColorScheme = lightColorScheme(
    primary = Palette.parentAccent,
    onPrimary = Palette.white,
    primaryContainer = Palette.parentAccentSoft,
    onPrimaryContainer = Palette.parentInk,
    secondary = Palette.lavender,
    background = Palette.parentBg,
    onBackground = Palette.parentInk,
    surface = Palette.parentSurface,
    onSurface = Palette.parentInk,
    surfaceVariant = Palette.parentBg,
    onSurfaceVariant = Palette.parentInkSoft,
    outline = Palette.parentLine,
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

@Composable
fun ParentTheme(rtl: Boolean, content: @Composable () -> Unit) {
    val family = parentFontFamily(rtl)
    val typography = Typography(
        headlineMedium = TextStyle(fontFamily = family, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontFamily = family, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontFamily = family, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = TextStyle(fontFamily = family, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodySmall = TextStyle(fontFamily = family, fontSize = 13.sp, lineHeight = 18.sp),
    )
    CompositionLocalProvider(
        LocalThemeMode provides ThemeMode.PARENT,
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = parentScheme(), typography = typography) {
            Box(Modifier.fillMaxSize().background(Palette.parentBg)) { content() }
        }
    }
}
