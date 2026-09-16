package quest.feature.school

import androidx.compose.ui.graphics.Color
import quest.api.dto.FontChoice
import quest.api.dto.SchoolTheme
import quest.api.dto.WorldPalette
import quest.feature.content.data.FakeContentApi
import quest.ui.design.AvatarColors
import quest.ui.design.DesignTokens
import quest.ui.design.Palette
import quest.ui.design.parentThemeScheme
import quest.ui.design.parseThemeColor
import quest.ui.design.schoolThemeOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §3: a school's theme JSON becomes the runtime overrides every screen reads. */
class SchoolThemeMappingTest {

    @Test fun everyRoleOfARealThemeIsMapped() {
        val overrides = schoolThemeOverrides(FakeContentApi.alNoorTheme)
        assertEquals(Color(0xFFE7F2EC), overrides.primary)
        assertEquals(Color(0xFF13301F), overrides.primaryInk)
        assertEquals(Color(0xFF1F6B4A), overrides.accent)
        assertEquals(Color(0xFFF4F7F4), overrides.ground)
        assertEquals(Color(0xFFC9DCD1), overrides.softBorder)
        assertEquals(Color(0xFF2E7D57), overrides.mascotColor)
        assertEquals(Color(0xFF7FD1B9), overrides.worldPalettes.math)
        assertEquals(Color(0xFFF2C75C), overrides.worldPalettes.english)
        assertEquals("nunito", overrides.fontChoice)
    }

    @Test fun aRoleWithNoValidColourKeepsItsToken() {
        val overrides = schoolThemeOverrides(SchoolTheme(primary = "not a colour", accent = "", ground = "#GGGGGG", worldPalettes = emptyMap()))
        assertNull(overrides.primary)
        assertNull(overrides.accent)
        assertNull(overrides.ground)
        assertNull(overrides.worldPalettes.math)
        assertNull(overrides.worldPalettes.english)
        // And the scheme falls straight back to the design tokens, so the app still looks like itself.
        assertEquals(Palette.parentAccent, parentThemeScheme(overrides).primary)
        assertEquals(Palette.parentSurface, parentThemeScheme(overrides).surface)
        assertEquals(Palette.parentBg, parentThemeScheme(overrides).background)
    }

    @Test fun hexShapesTheServerMaySend() {
        assertEquals(Color(0xFF1F6B4A), parseThemeColor("#1F6B4A"))
        assertEquals(Color(0xFF1F6B4A), parseThemeColor("  1f6b4a  "))
        assertEquals(Color(0xFFFFCC00), parseThemeColor("#FC0"))
        assertEquals(Color(0x801F6B4A), parseThemeColor("#1F6B4A80"))
        assertNull(parseThemeColor(null))
        assertNull(parseThemeColor(""))
        assertNull(parseThemeColor("#12345"))
        assertNull(parseThemeColor("rgb(1,2,3)"))
    }

    @Test fun theDefaultThemeIsTheTokenSet() {
        // `SchoolTheme()`'s defaults mirror the theme the server builds from design/tokens.json, so a school nobody has
        // themed lands on `hq.color.surface` / `hq.color.bg` / `hq.color.rule` rather than on something invented here.
        val overrides = schoolThemeOverrides(SchoolTheme())
        assertEquals(DesignTokens.colorSurface, overrides.primary)
        assertEquals(DesignTokens.colorBg, overrides.ground)
        assertEquals(DesignTokens.colorInk, overrides.primaryInk)
        assertEquals(DesignTokens.colorRule, overrides.softBorder)
        assertEquals(DesignTokens.colorAccentStrong, overrides.accent)

        val scheme = parentThemeScheme(overrides)
        assertEquals(DesignTokens.colorAccentStrong, scheme.primary, "a filled action carries the theme's accent")
        assertEquals(DesignTokens.colorSurface, scheme.surface, "a card carries the brand surface…")
        assertEquals(DesignTokens.colorInk, scheme.onSurface, "…and the ink the server measured against it")
        assertEquals(DesignTokens.colorBg, scheme.background)
        assertEquals(DesignTokens.colorRule, scheme.outline)
    }

    @Test fun theMascotColourOnlyEverRecoloursTheMascot() {
        val mascot = Color(0xFF2E7D57)
        assertEquals(mascot, AvatarColors.body(AvatarColors.MASCOT, mascot))
        assertEquals(AvatarColors.body("sun"), AvatarColors.body("sun", mascot))
        assertEquals(AvatarColors.body("lavender"), AvatarColors.body("lavender", mascot))
        // Unthemed, the mascot key is the sky body it has always been — the existing screenshots must not move.
        assertEquals(AvatarColors.body("sky"), AvatarColors.body(AvatarColors.MASCOT, null))
        assertEquals(AvatarColors.bodyDark("sky"), AvatarColors.bodyDark(AvatarColors.MASCOT, null))
        assertTrue(AvatarColors.MASCOT !in AvatarColors.keys, "the mascot is not one of the four avatar swatches")
    }

    @Test fun anUnshippedFontFallsBackToNunitoAndSaysWhichItWanted() {
        assertEquals("baloo", schoolThemeOverrides(SchoolTheme(fontChoice = FontChoice.BALOO)).fontChoice)
        assertEquals("fredoka", schoolThemeOverrides(SchoolTheme(fontChoice = FontChoice.FREDOKA)).fontChoice)
    }

    @Test fun onlyTheTwoSubjectWorldsAreRead() {
        val theme = SchoolTheme(worldPalettes = mapOf("science" to WorldPalette(primary = "#123456")))
        val overrides = schoolThemeOverrides(theme)
        assertNull(overrides.worldPalettes.math)
        assertNull(overrides.worldPalettes.english)
    }
}
