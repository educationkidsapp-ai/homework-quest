package quest.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `./gradlew :shared-ui:checkTokens` — the design-token drift gate.
 *
 * `design/tokens.json` is the single source both front-ends generate from: the Angular dashboard writes its CSS
 * custom properties (`dashboard/tools/tokens.mjs`), `:shared-ui:generateDesignTokens` writes [DesignTokens]. This
 * test fails when a colour or size in `Tokens.kt` / `Theme.kt` goes back to a literal that the JSON spells
 * differently, which is the only way the two front-ends can quietly drift apart.
 *
 * Every assertion below is one mapped token. A token NOT asserted here is generated but not consumed by the app
 * yet (`space.*`, `motion.*`, `z.*`, `shadow.*`, `worldPalettes.*`, `mascotColor.*`, the web-only sizes and the
 * font families) — those are listed in [generatedButUnmapped] so the set stays explicit.
 */
class TokensDriftTest {

    // ---- hq.color.* -> Palette.parent* ------------------------------------------------------------------------
    @Test
    fun parentPaletteMatchesTokens() {
        assertColor("hq.color.bg", DesignTokens.colorBg, Palette.parentBg)
        assertColor("hq.color.surface", DesignTokens.colorSurface, Palette.parentSurface)
        assertColor("hq.color.ink", DesignTokens.colorInk, Palette.parentInk)
        assertColor("hq.color.ink-soft", DesignTokens.colorInkSoft, Palette.parentInkSoft)
        assertColor("hq.color.accent", DesignTokens.colorAccent, Palette.parentAccent)
        assertColor("hq.color.accent-soft", DesignTokens.colorAccentSoft, Palette.parentAccentSoft)
        assertColor("hq.color.accent-strong", DesignTokens.colorAccentStrong, Palette.parentAccentStrong)
        assertColor("hq.color.line", DesignTokens.colorLine, Palette.parentLine)
        assertColor("hq.color.rule", DesignTokens.colorRule, Palette.parentRule)
        assertColor("hq.color.disabled", DesignTokens.colorDisabled, Palette.parentDisabled)
        assertColor("hq.color.band-good", DesignTokens.colorBandGood, Palette.bandGood)
        assertColor("hq.color.band-mid", DesignTokens.colorBandMid, Palette.bandMid)
        assertColor("hq.color.band-look", DesignTokens.colorBandLook, Palette.bandLook)
    }

    // ---- hq.size.* -> AdminTokens / Dimens ---------------------------------------------------------------------
    @Test
    fun adminSizesMatchTokens() {
        assertDp("hq.size.nav-width", DesignTokens.sizeNavWidth, AdminTokens.navWidth)
        assertDp("hq.size.content-max-width", DesignTokens.sizeContentMaxWidth, AdminTokens.contentMaxWidth)
        assertDp("hq.size.rule", DesignTokens.sizeRule, AdminTokens.rule)
        assertDp("hq.size.rule-thin", DesignTokens.sizeRuleThin, AdminTokens.ruleThin)
        assertDp("hq.size.selected-border", DesignTokens.sizeSelectedBorder, AdminTokens.selectedBorder)
        assertDp("hq.size.row-height", DesignTokens.sizeRowHeight, AdminTokens.rowHeight)
        assertDp("hq.size.button-height", DesignTokens.sizeButtonHeight, AdminTokens.buttonHeight)
        assertDp("hq.size.input-height", DesignTokens.sizeInputHeight, AdminTokens.inputHeight)
        assertDp("hq.size.page-padding", DesignTokens.sizePagePadding, AdminTokens.pagePadding)
        assertDp("hq.size.gutter", DesignTokens.sizeGutter, AdminTokens.gutter)
        assertDp("hq.size.course-card", DesignTokens.sizeCourseCard, AdminTokens.courseCard)
        assertDp("hq.size.grade-card", DesignTokens.sizeGradeCard, AdminTokens.gradeCard)
        assertDp("hq.size.phone-width", DesignTokens.sizePhoneWidth, AdminTokens.phoneWidth)
        assertDp("hq.size.phone-height", DesignTokens.sizePhoneHeight, AdminTokens.phoneHeight)
        assertDp("hq.size.phone-bezel", DesignTokens.sizePhoneBezel, AdminTokens.phoneBezel)
        assertDp("hq.size.phone-corner", DesignTokens.sizePhoneCorner, AdminTokens.phoneCorner)
        assertDp("hq.size.progress-bar", DesignTokens.sizeProgressBar, AdminTokens.progressBar)
        assertDp("hq.size.spinner", DesignTokens.sizeSpinner, AdminTokens.spinner)
        assertDp("hq.size.drop-zone-height", DesignTokens.sizeDropZoneHeight, AdminTokens.dropZoneHeight)
        assertDp("hq.size.stop-list-width", DesignTokens.sizeStopListWidth, AdminTokens.stopListWidth)
        assertDp("hq.size.logo-size", DesignTokens.sizeLogoSize, AdminTokens.logoSize)
        assertDp("hq.size.radius", DesignTokens.sizeRadius, Dimens.radiusParent)
    }

    // ---- hq.font.* -> the parent-mode type scale ---------------------------------------------------------------
    @Test
    fun parentTypeScaleMatchesTokens() {
        val title = AdminType.title(FontFamily.Default)
        assertSp("hq.font.title-size", DesignTokens.fontTitleSize, title.fontSize)
        assertSp("hq.font.title-line", DesignTokens.fontTitleLine, title.lineHeight)
        assertEquals(DesignTokens.fontTitleWeight, title.fontWeight?.weight, "hq.font.title-weight drifted from AdminType.title")

        val input = AdminType.input(FontFamily.Default)
        assertSp("hq.font.input-size", DesignTokens.fontInputSize, input.fontSize)
        assertSp("hq.font.body-line", DesignTokens.fontBodyLine, input.lineHeight)
        assertEquals(DesignTokens.fontInputWeight, input.fontWeight?.weight, "hq.font.input-weight drifted from AdminType.input")

        val body: TextStyle = parentTypography(FontFamily.Default).titleMedium
        assertSp("hq.font.body-size", DesignTokens.fontBodySize, body.fontSize)
        assertSp("hq.font.body-line", DesignTokens.fontBodyLine, body.lineHeight)
    }

    // ---- the default scheme must be the token values, byte for byte ---------------------------------------------
    @Test
    fun emptyThemeOverridesChangeNothing() {
        assertTrue(ThemeOverrides().isEmpty, "the default ThemeOverrides must be empty")
        // ColorScheme has no equals(), so compare the roles an override can reach.
        val scheme = parentScheme()
        val withEmpty = parentScheme(ThemeOverrides())
        assertEquals(scheme.primary, withEmpty.primary)
        assertEquals(scheme.onPrimary, withEmpty.onPrimary)
        assertEquals(scheme.secondary, withEmpty.secondary)
        assertEquals(scheme.background, withEmpty.background)
        assertEquals(scheme.surfaceVariant, withEmpty.surfaceVariant)
        assertEquals(scheme.outline, withEmpty.outline)
        assertEquals(Palette.parentAccent, scheme.primary)
        assertEquals(Palette.white, scheme.onPrimary)
        assertEquals(Palette.parentAccentSoft, scheme.primaryContainer)
        assertEquals(Palette.parentSurface, scheme.surface)
        assertEquals(Palette.parentInk, scheme.onSurface)
        assertEquals(Palette.parentBg, scheme.background)
        assertEquals(Palette.parentLine, scheme.outline)
    }

    /** §3's three brand colours land on the Material roles their *job* implies, not the ones their names suggest. */
    @Test
    fun overridesReplaceTheirRole() {
        val accent = Color(0xFF1F6B4A)
        val brand = Color(0xFFE7F2EC)
        val brandInk = Color(0xFF13301F)
        val ground = Color(0xFFF4F7F4)
        val border = Color(0xFFC9DCD1)
        val scheme = parentScheme(ThemeOverrides(primary = brand, primaryInk = brandInk, accent = accent, ground = ground, softBorder = border))
        assertEquals(accent, scheme.primary, "a filled action carries `accent`")
        assertEquals(accent, scheme.secondary, "and so does a selection border")
        assertEquals(brand, scheme.surface, "`primary` is the light brand surface")
        assertEquals(brandInk, scheme.onSurface, "carrying the ink the server measured against it")
        assertEquals(ground, scheme.background)
        assertEquals(border, scheme.outline)
    }

    /** The label on an accent-filled button is chosen by luminance, because the server never measures that pair. */
    @Test
    fun theInkOnAnAccentFillFollowsItsLuminance() {
        assertEquals(Palette.white, inkOn(Color(0xFF1F6B4A)))
        assertEquals(Palette.parentInk, inkOn(Color(0xFFF2C75C)))
        assertEquals(Palette.white, parentScheme(ThemeOverrides(accent = Color(0xFF1F6B4A))).onPrimary)
        assertEquals(Palette.parentInk, parentScheme(ThemeOverrides(accent = Color(0xFFF2C75C))).onPrimary)
    }

    /** The soft container is derived from the accent, so a school without one keeps the token's own soft red. */
    @Test
    fun theSoftContainerIsTheAccentOverTheGround() {
        assertEquals(Palette.parentAccentSoft, parentScheme(ThemeOverrides()).primaryContainer)
        val tinted = softAccentOf(Color(0xFF1F6B4A), Color(0xFFFFFFFF))!!
        assertTrue(tinted.luminance() > 0.7f, "a 12 % tint of a dark accent on white is still a light surface")
        assertEquals(tinted, parentScheme(ThemeOverrides(softAccent = tinted)).primaryContainer)
        // The token's own pair is close to the same relationship, which is why one formula serves both.
        val tokenSoft = softAccentOf(Palette.parentAccent, Palette.parentBg)!!
        assertTrue((tokenSoft.luminance() - Palette.parentAccentSoft.luminance()) < 0.06f, "the derived tint is in the token's family")
    }

    // ---- the generated set itself -------------------------------------------------------------------------------
    /** Tokens generated for the app to use but not yet consumed by a hand-written value. Keep the list honest. */
    private val generatedButUnmapped = setOf(
        "hq.color.on-accent", "hq.color.on-ink", "hq.color.focus", "hq.color.skeleton",
        "hq.color.skeleton-shine", "hq.color.overlay",
        "hq.size.focus-ring", "hq.size.focus-ring-offset", "hq.size.touch-target",
        "hq.space.4", "hq.space.8", "hq.space.12", "hq.space.16", "hq.space.24", "hq.space.32", "hq.space.48",
        "hq.font.family-latin", "hq.font.family-arabic", "hq.font.family-mono",
        "hq.font.body-weight", "hq.font.label-size", "hq.font.label-line", "hq.font.label-weight",
        "hq.font.letter-spacing-title", "hq.font.letter-spacing-label",
        "hq.motion.fast", "hq.motion.base", "hq.motion.slow", "hq.motion.count-up", "hq.motion.shake",
        "hq.motion.stagger", "hq.motion.skeleton-delay", "hq.motion.shimmer", "hq.motion.undo-window",
        "hq.motion.ease", "hq.motion.ease-emphasised", "hq.motion.shake-distance", "hq.motion.page-enter-offset",
        "hq.motion.list-rise-offset", "hq.motion.lift-offset",
        "hq.z.base", "hq.z.sticky", "hq.z.nav", "hq.z.sticky-footer", "hq.z.overlay", "hq.z.dialog",
        "hq.z.undo", "hq.z.toastless-band",
        "hq.shadow.none", "hq.shadow.raise", "hq.shadow.lift", "hq.shadow.dialog", "hq.shadow.drag",
        "hq.worldPalettes.math.primary", "hq.worldPalettes.math.deep", "hq.worldPalettes.math.soft",
        "hq.worldPalettes.english.primary", "hq.worldPalettes.english.deep", "hq.worldPalettes.english.soft",
        "hq.mascotColor.body", "hq.mascotColor.body-dark", "hq.mascotColor.scarf",
    )

    private val mapped = setOf(
        "hq.color.bg", "hq.color.surface", "hq.color.ink", "hq.color.ink-soft", "hq.color.accent",
        "hq.color.accent-soft", "hq.color.accent-strong", "hq.color.line", "hq.color.rule", "hq.color.disabled",
        "hq.color.band-good", "hq.color.band-mid", "hq.color.band-look",
        "hq.size.nav-width", "hq.size.content-max-width", "hq.size.rule", "hq.size.rule-thin",
        "hq.size.selected-border", "hq.size.row-height", "hq.size.button-height", "hq.size.input-height",
        "hq.size.page-padding", "hq.size.gutter", "hq.size.course-card", "hq.size.grade-card",
        "hq.size.phone-width", "hq.size.phone-height", "hq.size.phone-bezel", "hq.size.phone-corner",
        "hq.size.progress-bar", "hq.size.spinner", "hq.size.drop-zone-height", "hq.size.stop-list-width",
        "hq.size.logo-size", "hq.size.radius",
        "hq.font.title-size", "hq.font.title-line", "hq.font.title-weight",
        "hq.font.body-size", "hq.font.body-line", "hq.font.input-size", "hq.font.input-weight",
    )

    /**
     * Forward compatibility: a token added to `design/tokens.json` (a school theme group, a new size) must be
     * generated and reachable, but must not fail this build. Only a token that vanishes is a problem, because
     * something in Kotlin is mapped to it.
     */
    @Test
    fun everyMappedTokenStillExists() {
        val missing = mapped.filterNot { it in DesignTokens.all }
        assertTrue(missing.isEmpty(), "design/tokens.json no longer defines: $missing — update Tokens.kt/Theme.kt")
        val vanished = generatedButUnmapped.filterNot { it in DesignTokens.all }
        assertTrue(vanished.isEmpty(), "these tokens disappeared from design/tokens.json: $vanished")
        val unknown = DesignTokens.all.keys - mapped - generatedButUnmapped
        // New tokens are fine — they just are not consumed yet. Fail only if the generator produced nothing.
        assertTrue(DesignTokens.all.size >= mapped.size + generatedButUnmapped.size, "the generator dropped tokens")
        if (unknown.isNotEmpty()) println("tokens: ${unknown.size} new token(s) generated but not consumed yet: $unknown")
    }

    @Test
    fun rawValuesAreCarriedThrough() {
        assertEquals("#EC3013", DesignTokens.all["hq.color.accent"])
        assertEquals("240px", DesignTokens.all["hq.size.nav-width"])
        assertEquals("150ms", DesignTokens.all["hq.motion.fast"])
        assertEquals("rgba(32, 30, 29, 0.45)", DesignTokens.all["hq.color.overlay"])
    }

    // ---- assertion helpers ---------------------------------------------------------------------------------------
    private fun assertColor(token: String, generated: Color, handWritten: Color) {
        if (generated != handWritten) {
            fail("$token = ${DesignTokens.all[token]} (generated $generated) but the hand-written value is $handWritten. " +
                "Read it from DesignTokens instead of hard-coding it, or change design/tokens.json.")
        }
    }

    private fun assertDp(token: String, generated: Dp, handWritten: Dp) {
        if (generated != handWritten) {
            fail("$token = ${DesignTokens.all[token]} (generated $generated) but the hand-written value is $handWritten.")
        }
    }

    private fun assertSp(token: String, generated: TextUnit, handWritten: TextUnit) {
        if (generated != handWritten) {
            fail("$token = ${DesignTokens.all[token]} (generated $generated) but the hand-written value is $handWritten.")
        }
    }
}
