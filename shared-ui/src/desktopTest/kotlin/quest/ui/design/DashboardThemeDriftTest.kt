package quest.ui.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The app's palette against the dashboard's own style sheet. `DashboardPalette` / `BrandColors` are hand-written Kotlin
 * and `dashboard/src/styles/_theme.scss` is hand-written SCSS (published as `docs/brand/palette.md`); this reads the
 * SCSS and fails when a ramp step, a role, a radius, the control height, the brand gradient or the page wash the app
 * mirrors no longer resolves to the same value — in the light set and under `html.dark`. It is the app-side half of the
 * token gate: the generated tokens are covered by `TokensDriftTest`, and everything the dashboard layers on top of them
 * is covered here, colours included.
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
        // `color-mix(in srgb, <colour> N%, transparent)` is that colour at N % alpha.
        Regex("""^color-mix\(in srgb, var\((--hq-[\w-]+)\) (\d+)%, transparent\)$""").find(raw)?.let { m ->
            return resolve(m.groupValues[1], scope).copy(alpha = m.groupValues[2].toInt() / 100f)
        }
        fail("$name: `$raw` is not a colour this test can read")
    }

    /** [onSurface] lays a translucent web value over that surface first — the app stores such tints already composited. */
    private fun assertRole(name: String, scope: Map<String, String>, app: Color, onSurface: Color? = null) {
        val web = resolve(name, scope).let { if (onSurface != null && it.alpha < 1f) it.compositeOver(onSurface) else it }
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
        // The fill of an action is the token `hq.color.accent` — the dashboard's `brand-fill`, the first blue a white
        // label clears AA on; the dashboard's own `accent` role is the step it uses for text, which is `brandInk` here.
        assertRole("--hq-color-brand-fill", scope, p.brand)
        assertRole("--hq-color-accent-soft", scope, p.brandSoft, onSurface = p.surface)
        assertRole("--hq-color-accent-ink", scope, p.brandInk)
        assertRole("--hq-color-secondary", scope, p.secondary)
        assertRole("--hq-color-secondary-soft", scope, p.secondarySoft, onSurface = p.surface)
        assertRole("--hq-color-secondary-ink", scope, p.secondaryInk)
        assertRole("--hq-color-tertiary", scope, p.tertiary)
        assertRole("--hq-color-tertiary-soft", scope, p.tertiarySoft, onSurface = p.surface)
        assertRole("--hq-color-tertiary-ink", scope, p.tertiaryInk)
        assertRole("--hq-gradient-brand-from", scope, p.gradient.first())
        assertRole("--hq-gradient-brand-to", scope, p.gradient.last())
        assertRole("--hq-gradient-brand-fill-from", scope, p.gradientText.first())
        assertRole("--hq-gradient-brand-to", scope, p.gradientText.last())
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

    /** Every step of the four brand ramps the app declares is the dashboard's step of the same name. */
    /** The seven palette colours of `design/tokens.json` are the same seven steps of the dashboard's ramps. */
    @Test fun theGeneratedTokensAreTheRampSteps() {
        assertRole("--hq-color-brand-500", light, DesignTokens.colorAccent)
        assertRole("--hq-color-brand-600", light, DesignTokens.colorAccentStrong)
        assertRole("--hq-color-brand-50", light, DesignTokens.colorAccentSoft)
        assertRole("--hq-color-brand-400", light, DesignTokens.colorGradientFrom)
        assertRole("--hq-color-brand-700", light, DesignTokens.colorGradientTo)
        assertRole("--hq-color-magenta-600", light, DesignTokens.colorSecondary)
        assertRole("--hq-color-orange-400", light, DesignTokens.colorTertiary)
        // …and the app's palette is built from them, not from literals.
        assertEquals(DesignTokens.colorAccent, DashboardPalette.Light.brand)
        assertEquals(listOf(DesignTokens.colorGradientFrom, DesignTokens.colorGradientTo), DashboardPalette.Light.gradient)
        assertEquals(DesignTokens.colorSecondary, DashboardPalette.Light.secondary)
        assertEquals(DesignTokens.colorTertiary, DashboardPalette.Light.tertiary)
    }

    @Test fun theBrandRampsAreTheDashboards() {
        val blue = mapOf(25 to BrandColors.blue25, 50 to BrandColors.blue50, 100 to BrandColors.blue100, 200 to BrandColors.blue200, 300 to BrandColors.blue300,
            400 to BrandColors.blue400, 500 to BrandColors.blue500, 600 to BrandColors.blue600, 700 to BrandColors.blue700, 800 to BrandColors.blue800,
            900 to BrandColors.blue900, 950 to BrandColors.blue950)
        val magenta = mapOf(50 to BrandColors.magenta50, 100 to BrandColors.magenta100, 200 to BrandColors.magenta200, 300 to BrandColors.magenta300,
            400 to BrandColors.magenta400, 500 to BrandColors.magenta500, 600 to BrandColors.magenta600, 700 to BrandColors.magenta700,
            800 to BrandColors.magenta800, 900 to BrandColors.magenta900)
        val orange = mapOf(50 to BrandColors.orange50, 100 to BrandColors.orange100, 200 to BrandColors.orange200, 300 to BrandColors.orange300,
            400 to BrandColors.orange400, 500 to BrandColors.orange500, 600 to BrandColors.orange600, 700 to BrandColors.orange700, 800 to BrandColors.orange800)
        val warning = mapOf(50 to BrandColors.warning50, 100 to BrandColors.warning100, 500 to BrandColors.warning500, 700 to BrandColors.warning700)
        blue.forEach { (step, colour) -> assertRole("--hq-color-brand-$step", light, colour) }
        magenta.forEach { (step, colour) -> assertRole("--hq-color-magenta-$step", light, colour) }
        orange.forEach { (step, colour) -> assertRole("--hq-color-orange-$step", light, colour) }
        warning.forEach { (step, colour) -> assertRole("--hq-color-warning-$step", light, colour) }
    }

    /** `--hq-gradient-ground`: the three logo hues at their palest, at the brand angle; nothing on dark. */
    @Test fun thePageWashIsTheDashboardsGroundGradient() {
        assertTrue(Regex("""--hq-gradient-ground: linear-gradient\(\s*var\(--hq-gradient-angle\),\s*var\(--hq-color-brand-50\) 0%,\s*var\(--hq-color-magenta-50\) 50%,\s*var\(--hq-color-orange-50\) 100%\s*\)""").containsMatchIn(styles), "the dashboard's page wash has changed")
        assertEquals("135deg", light["--hq-gradient-angle"])
        assertEquals(listOf(BrandColors.blue50, BrandColors.magenta50, BrandColors.orange50), DashboardPalette.Light.groundWash)
        assertEquals("none", dark["--hq-gradient-ground"])
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
            assertTrue(ratio(p.secondaryInk, p.secondarySoft) >= 4.5, "$name secondary ink on its tint")
            assertTrue(ratio(p.tertiaryInk, p.tertiarySoft) >= 4.5, "$name tertiary ink on its tint")
            assertTrue(ratio(p.brandInk, p.brandSoft) >= 4.5, "$name brand ink on the primary container")
        }
    }
}
