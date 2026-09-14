package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Subject
import quest.api.progress.Band
import quest.api.samples.HotSoupSeed
import quest.feature.auth.presentation.SignInContract
import quest.feature.auth.presentation.SignInScreen
import quest.feature.children.presentation.AddChildContract
import quest.feature.children.presentation.AddChildScreen
import quest.feature.children.presentation.ChildPickerScreen
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.SkillReport
import quest.feature.parent.presentation.CalendarContract
import quest.feature.parent.presentation.CalendarScreen
import quest.feature.parent.presentation.LessonPanelScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentHomeContract
import quest.feature.parent.presentation.ParentHomeScreen
import quest.feature.parent.presentation.PinContract
import quest.feature.parent.presentation.PinScreen
import quest.feature.parent.presentation.ProgressContract
import quest.feature.parent.presentation.ProgressScreen
import quest.feature.parent.presentation.SettingsContract
import quest.feature.parent.presentation.SettingsScreen
import quest.feature.parent.presentation.Strings
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

class ParentScreensScreenshotTest {
    private val today = LocalDate(2026, 9, 14)
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val omar = Child("c2", "Omar", "mint", Curriculum.AMERICAN, 2)

    private fun shot(name: String, strings: Strings = Strings.en, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) { ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } } }
        assertTrue(f.length() > 1000)
    }

    @Test fun signIn() = shot("40-sign-in") { s -> SignInScreen(SignInContract.State(email = "parent@example.com"), s, {}) }
    @Test fun addChild() = shot("41-add-child") { s -> AddChildScreen(AddChildContract.State(name = "Maya", avatar = "sun", loaded = true), s, {}) }
    @Test fun childPicker() = shot("42-child-picker") { s -> ChildPickerScreen(listOf(maya, omar), s, {}, {}) }
    @Test fun pin() = shot("43-pin") { s -> PinScreen(PinContract.State(PinContract.Mode.ENTER, "12"), {}, s) }
    @Test fun home() = shot("44-parent-home") { s -> ParentHomeScreen(ParentHomeContract.State(false, listOf(maya, omar), maya, listOf(CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH), listOf("l1", "l2"), listOf("l2")))), s, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun homeArabic() = shot("44b-parent-home-ar", Strings.ar) { s -> ParentHomeScreen(ParentHomeContract.State(false, listOf(maya), maya, listOf(CalendarDay(today, listOf(Subject.ENGLISH), listOf("l2"), listOf("l2")))), s, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun calendar() = shot("45-calendar") { s -> CalendarScreen(CalendarContract.State(2026, 9, today, today, mapOf(today to CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH), listOf("l1", "l2"), listOf("l2")), LocalDate(2026, 9, 11) to CalendarDay(LocalDate(2026, 9, 11), listOf(Subject.ENGLISH), listOf("l0"), listOf("l0")))), s, {}, {}) }
    @Test fun progress() = shot("46-progress") { s -> ProgressScreen(ProgressContract.State(false, listOf(
        SkillReport("s1", "Counting by 2s", Subject.MATH, Band.GOING_WELL, "most of the time", 14, null),
        SkillReport("s2", "The sh sound", Subject.ENGLISH, Band.NEEDS_ANOTHER_LOOK, "some of the time", 7, null),
        SkillReport("s3", "Retelling a story", Subject.ENGLISH, null, null, 0, null))), s) }
    @Test fun settings() = shot("47-settings") { s -> SettingsScreen(SettingsContract.State(false, ParentSettings("en"), "Maya", "c1"), s, {}, {}, {}) }
    @Test fun settingsArabic() = shot("47b-settings-ar", Strings.ar) { s -> SettingsScreen(SettingsContract.State(false, ParentSettings("ar"), "مايا", "c1"), s, {}, {}, {}) }
    @Test fun lessonPanel() = shot("48-lesson-panel") { s -> LessonPanelScreen(HotSoupSeed.lesson, s) }
    @Test fun lessonPanelArabic() = shot("48b-lesson-panel-ar", Strings.ar) { s -> LessonPanelScreen(HotSoupSeed.lesson, s) }
}
