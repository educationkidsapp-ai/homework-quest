package quest.feature.school

import quest.feature.content.data.FakeContentApi
import quest.feature.school.presentation.unthemed
import quest.ui.design.AvatarColors
import quest.ui.design.ThemeOverrides
import quest.ui.design.parentThemeScheme
import quest.ui.design.schoolThemeOverrides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The app root never provides a *partial* [ThemeOverrides]: to cross-fade a colour it has to name what that colour is
 * fading from, so every role is always filled in. That makes the base set load-bearing — get one wrong and the app
 * with no school joined quietly changes colour — so it is asserted against the empty overrides, role by role.
 */
class SchoolThemeHostTest {

    @Test fun theUnthemedBaseIsExactlyWhatNoOverridesWouldProduce() {
        val tokens = parentThemeScheme(ThemeOverrides())
        val base = parentThemeScheme(unthemed)
        assertEquals(tokens.primary, base.primary)
        assertEquals(tokens.onPrimary, base.onPrimary)
        assertEquals(tokens.primaryContainer, base.primaryContainer)
        assertEquals(tokens.onPrimaryContainer, base.onPrimaryContainer)
        assertEquals(tokens.secondary, base.secondary)
        assertEquals(tokens.onSecondary, base.onSecondary)
        assertEquals(tokens.background, base.background)
        assertEquals(tokens.onBackground, base.onBackground)
        assertEquals(tokens.surface, base.surface)
        assertEquals(tokens.onSurface, base.onSurface)
        assertEquals(tokens.surfaceVariant, base.surfaceVariant)
        assertEquals(tokens.onSurfaceVariant, base.onSurfaceVariant)
        assertEquals(tokens.outline, base.outline)
    }

    @Test fun theUnthemedMascotIsPipsOwnBlue() {
        assertEquals(AvatarColors.body("sky"), unthemed.mascotColor)
        assertEquals(AvatarColors.body(AvatarColors.MASCOT, unthemed.mascotColor), unthemed.mascotColor)
    }

    /** And a real school does move every role, so the base is not being applied on top of the theme. */
    @Test fun aSchoolThemeReachesEveryRole() {
        val school = parentThemeScheme(schoolThemeOverrides(FakeContentApi.alNoorTheme))
        val base = parentThemeScheme(unthemed)
        assertNotEquals(base.primary, school.primary)
        assertNotEquals(base.primaryContainer, school.primaryContainer)
        assertNotEquals(base.surface, school.surface)
        assertNotEquals(base.onSurface, school.onSurface)
        assertNotEquals(base.background, school.background)
        assertNotEquals(base.outline, school.outline)
    }
}
