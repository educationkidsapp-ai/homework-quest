package quest.ui.design

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's palette against the dashboard's own style sheet. `DashboardPalette` is hand-written Kotlin and
 * `dashboard/src/styles/_theme.scss` is hand-written SCSS; this reads the SCSS and fails when a **neutral, a status
 * colour, a radius or the control height** the app mirrors no longer resolves to the same value — in the light set and
 * under `html.dark`. It is the app-side half of the token gate: the 43 generated tokens are covered by
 * `TokensDriftTest`, and the roles the dashboard layers on top of them are covered here.
 *
 * **Brand colours are not compared.** Since the MySchool logo, the app's primary, secondary and tertiary come from the
 * logo ([BrandColors]) while the dashboard still wears its own blue-violet; those roles are held to AA contrast here
 * instead, and the values are listed in the PR that introduced them so the dashboard can adopt them.
 */
class DashboardThemeDriftTest {
    private val styles = repoFile("dashboard/src/styles/_theme.scss").readText()
    private val light = block(styles, ":root")
    private val dark = block(styles, "html.dark")

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }
            ?: fail("$path not found above ${File("").absolutePath}")

    /** The declarations of the first `selector { … }` block, as `--name` → raw value. */
    private fun block(text: String, selector: String): Map<String, String> {
        val start = Regex("""(?m)^${Regex.escape(selector)}\s*\{""").find(text) ?: fail("no `$selector` block in _theme.scss")
        val end = text.indexOf("\n}", start.range.last)
        return Regex("""(--hq-[\w-]+):\s*([^;]+);""").findAll(text.substring(start.range.last, end))
            .associate { it.groupValues[1] to it.groupValues[2].replace("!important", "").trim() }
    }

    /** A role's colour: follows `var(--…)` through [scope] and then `:root`, and reads `#hex` and `rgb(r g b / a)`. */
    private fun resolve(name: String, scope: Map<String, String>): Color {
        val raw = scope[name] ?: light[name] ?: fail("$name is not declared in _theme.scss")
        Regex("""^var\((--hq-[\w-]+)\)$""").find(raw)?.let { return resolve(it.groupValues[1], scope) }
        Regex("""^#([0-9a-fA-F]{6})$""").find(raw)?.let { return Color(("FF" + it.groupValues[1]).toLong(16)) }
        Regex("""^rgb\((\d+) (\d+) (\d+) / ([\d.]+)\)$""").find(raw)?.let { m ->
            val (r, g, b, a) = m.destructured
            return Color(r.toInt(), g.toInt(), b.toInt()).copy(alpha = a.toFloat())
        }
        fail("$name: `$raw` is not a colour this test can read")
    }

    private fun assertRole(name: String, scope: Map<String, String>, app: Color) {
        val web = resolve(name, scope)
        assertEquals(web.value, app.value, "$name is ${hex(web)} on the dashboard and ${hex(app)} in the app")
    }

    private fun hex(c: Color) = "#%02X%02X%02X@%.2f".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), c.alpha)

    private fun assertRoles(scope: Map<String, String>, p: DashboardPalette) {
        assertRole("--hq-color-bg", scope, p.bg)
        assertRole("--hq-color-surface", scope, p.surface)
        assertRole("--hq-color-surface-raised", scope, p.surfaceRaised)
        assertRole("--hq-color-neutral-soft", scope, p.bgSubtle)
        assertRole("--hq-color-ink", scope, p.ink)
        assertRole("--hq-color-ink-soft", scope, p.inkSoft)
        assertRole("--hq-color-ink-muted", scope, p.inkMuted)
        // `inkLight` is the disabled glyph on dark; on light the app keeps the lighter gray-300 for decorative glyphs.
        if (p.dark) assertRole("--hq-color-disabled", scope, p.inkLight)
        assertRole("--hq-color-rule", scope, p.rule)
        assertRole("--hq-color-control-rule", scope, p.ruleControl)
        assertRole("--hq-color-divider", scope, p.divider)
        assertRole("--hq-color-success-ink", scope, p.success)
        assertRole("--hq-color-success-soft", scope, p.successBg)
        assertRole("--hq-color-warning-ink", scope, p.warning)
        assertRole("--hq-color-warning-soft", scope, p.warningBg)
        assertRole("--hq-color-error-ink", scope, p.error)
        assertRole("--hq-color-error-soft", scope, p.errorBg)
        assertRole("--hq-color-overlay", scope, p.overlay)
    }

    @Test fun theLightPaletteIsTheDashboardsRoot() = assertRoles(light, DashboardPalette.Light)

    @Test fun theDarkPaletteIsTheDashboardsHtmlDark() = assertRoles(dark, DashboardPalette.Dark)

    @Test fun titlesAndBordersUseTheDashboardsRamp() {
        assertRole("--hq-color-gray-900", light, DashboardPalette.Light.inkStrong)
        assertRole("--hq-color-success-100", light, DashboardPalette.Light.successBorder)
        assertRole("--hq-color-warning-100", light, DashboardPalette.Light.warningBorder)
        assertRole("--hq-color-error-100", light, DashboardPalette.Light.errorBorder)
    }

    @Test fun radiiAndControlHeightAreTheDashboards() {
        fun px(name: String) = Regex("""^(\d+)px$""").find(light[name] ?: fail("$name missing"))?.groupValues?.get(1)?.toFloat() ?: fail("$name is not px")
        assertEquals(px("--hq-radius-xs"), DashboardTokens.radiusXs.value)
        assertEquals(px("--hq-radius-control"), DashboardTokens.radiusSm.value)
        assertEquals(px("--hq-radius-tile"), DashboardTokens.radiusMd.value)
        assertEquals(px("--hq-radius-card"), DashboardTokens.radiusLg.value)
        assertEquals(px("--hq-radius-pill"), DashboardTokens.radiusFull.value)
        assertEquals(px("--hq-size-control-height"), DashboardTokens.buttonHeight.value)
    }

    @Test fun theBrandIsTheLogos() {
        // The sampled values of docs/brand/myschool-logo-source.jpg: the diamond's two ends, the magenta and the orange.
        assertEquals(Color(0xFF0C9DE2), BrandColors.blue500)
        assertEquals(Color(0xFF0B55B5), BrandColors.blue700)
        assertEquals(Color(0xFFB8037A), BrandColors.magenta500)
        assertEquals(Color(0xFFF99C0A), BrandColors.orange500)
        listOf(DashboardPalette.Light, DashboardPalette.Dark).forEach { p ->
            assertEquals(listOf(BrandColors.blue500, BrandColors.blue700), p.gradient, "the gradient is the diamond's, light to deep")
        }
    }

    /** The page wash keeps the dashboard shell's shape — three soft stops, flat on dark — in the logo's hues. */
    @Test fun thePageWashIsThreeSoftStopsAndFlatOnDark() {
        assertEquals(3, DashboardPalette.Light.groundWash.size)
        assertEquals(DashboardPalette.Light.bg, DashboardPalette.Light.groundWash[1])
        assertTrue(DashboardPalette.Dark.groundWash.all { it == DashboardPalette.Dark.bg })
    }

    /** WCAG contrast of the text roles on the surfaces they sit on, in both palettes (AA: 4.5 for text). */
    @Test fun textRolesClearAaInBothPalettes() {
        fun lum(c: Color): Double {
            fun ch(v: Float) = if (v <= 0.03928f) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        fun over(fg: Color, bg: Color) = Color(
            fg.red * fg.alpha + bg.red * (1 - fg.alpha), fg.green * fg.alpha + bg.green * (1 - fg.alpha), fg.blue * fg.alpha + bg.blue * (1 - fg.alpha),
        )
        fun ratio(fg: Color, bg: Color): Double {
            val a = lum(over(fg, bg)); val b = lum(bg)
            return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }
        listOf(DashboardPalette.Light, DashboardPalette.Dark).forEach { p ->
            val name = if (p.dark) "dark" else "light"
            listOf(p.surface, p.bg).forEach { ground ->
                assertTrue(ratio(p.ink, ground) >= 4.5, "$name ink")
                assertTrue(ratio(p.inkStrong, ground) >= 4.5, "$name inkStrong")
                assertTrue(ratio(p.inkSoft, ground) >= 4.5, "$name inkSoft")
            }
            assertTrue(ratio(p.success, over(p.successBg, p.surface)) >= 4.5, "$name success on its tint")
            assertTrue(ratio(p.warning, over(p.warningBg, p.surface)) >= 4.5, "$name warning on its tint")
            assertTrue(ratio(p.error, over(p.errorBg, p.surface)) >= 4.5, "$name error on its tint")
            // Brand roles: every pair that carries text, in both palettes.
            assertTrue(ratio(p.onBrand, p.brand) >= 4.5, "$name label on the primary")
            p.gradientText.forEach { assertTrue(ratio(p.onBrand, it) >= 4.5, "$name label across the text gradient") }
            listOf(p.surface, p.bg).forEach { ground ->
                assertTrue(ratio(p.brandInk, ground) >= 4.5, "$name brand ink")
                assertTrue(ratio(p.secondaryInk, ground) >= 4.5, "$name secondary ink")
                assertTrue(ratio(p.tertiaryInk, ground) >= 4.5, "$name tertiary ink")
            }
            assertTrue(ratio(p.onSecondary, p.secondary) >= 4.5, "$name label on the secondary")
            assertTrue(ratio(p.onTertiary, p.tertiary) >= 4.5, "$name label on the tertiary")
            assertTrue(ratio(p.secondaryInk, over(p.secondarySoft, p.surface)) >= 4.5, "$name secondary ink on its tint")
            assertTrue(ratio(p.tertiaryInk, over(p.tertiarySoft, p.surface)) >= 4.5, "$name tertiary ink on its tint")
            assertTrue(ratio(p.brandInk, softAccentOf(p.brand, p.surface, p.dark)!!) >= 4.5, "$name brand ink on the primary container")
        }
    }
}
