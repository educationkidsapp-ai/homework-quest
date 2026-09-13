package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.datetime.LocalDate
import quest.api.dto.ApiError
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.api.dto.Unsure
import quest.core.design.ParentTheme
import quest.feature.lesson.domain.Lesson
import quest.feature.lesson.domain.Skill
import quest.feature.lesson.presentation.AddLessonContract
import quest.feature.lesson.presentation.AddLessonScreen
import quest.feature.lesson.presentation.ConfirmContract
import quest.feature.lesson.presentation.ConfirmSkillsScreen
import quest.feature.lesson.presentation.ReadingContract
import quest.feature.lesson.presentation.ReadingScreen
import quest.feature.lesson.presentation.TypedTaskScreen
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.domain.ChildProfile
import quest.feature.parent.domain.ParentSettings
import quest.feature.parent.domain.SkillReport
import quest.feature.parent.presentation.CalendarContract
import quest.feature.parent.presentation.CalendarScreen
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
import quest.feature.practice.domain.Band
import kotlin.test.Test
import kotlin.test.assertTrue

class ParentScreensScreenshotTest {
    private val today = LocalDate(2026, 9, 14)
    private val profile = ChildProfile("c", "Maya", "sun", 1, "IB PYP", listOf("en"), true)
    private val lesson = Lesson("l1", today, Subject.MATH, LessonStatus.READY, listOf("monday-math.pdf"), 0)
    private val skills = listOf(
        Skill("s1", "l1", "Counting by 2s", Subject.MATH, "number line jumps", 0.95, true, listOf("2, 4, 6, 8"), lessonDate = today),
        Skill("s2", "l1", "Number bonds to 10", Subject.MATH, "ten frames", 0.55, false, listOf("7 + 3 = 10"), Unsure(listOf("Number bonds to 10", "Adding two numbers"), "Slide 6 shows 7 + 3 in a ten frame. Was this about making 10, or adding in general?")),
    )

    private fun shot(name: String, strings: Strings = Strings.en, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) { ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } } }
        assertTrue(f.length() > 1000)
    }

    @Test fun pin() = shot("16-pin") { s -> PinScreen(PinContract.State(PinContract.Mode.ENTER, "12"), {}, s) }
    @Test fun pinArabic() = shot("16b-pin-ar", Strings.ar) { s -> PinScreen(PinContract.State(PinContract.Mode.CREATE), {}, s) }

    @Test fun home() = shot("17-parent-home") { s ->
        ParentHomeScreen(ParentHomeContract.State(false, profile, listOf(ParentHomeContract.LessonCard(lesson, skills), ParentHomeContract.LessonCard(lesson.copy(id = "l2", subject = Subject.ENGLISH, status = LessonStatus.NEEDS_CONFIRMATION), emptyList()))), s, {}, {}, {}, {}, {}, {})
    }
    @Test fun homeArabic() = shot("17b-parent-home-ar", Strings.ar) { s ->
        ParentHomeScreen(ParentHomeContract.State(false, profile, listOf(ParentHomeContract.LessonCard(lesson, skills))), s, {}, {}, {}, {}, {}, {})
    }

    @Test fun addLesson() = shot("18-add-lesson") { s ->
        AddLessonScreen(AddLessonContract.State(Subject.MATH, files = listOf(quest.core.platform.PickedFile("monday-math.pdf", "application/pdf", ByteArray(40_000)))), s, {}, {}, {})
    }
    @Test fun typedTask() = shot("19-typed-task") { s -> TypedTaskScreen(AddLessonContract.State(Subject.ENGLISH, typedTask = "Practise the sh words: ship, shop, sheep"), s, {}) }

    @Test fun readingUploading() = shot("20a-reading-uploading") { s -> ReadingScreen(ReadingContract.State(LessonStatus.UPLOADING), s, {}, {}, {}) }
    @Test fun readingReading() = shot("20b-reading-reading") { s -> ReadingScreen(ReadingContract.State(LessonStatus.READING), s, {}, {}, {}) }
    @Test fun readingError() = shot("20c-reading-error") { s -> ReadingScreen(ReadingContract.State(LessonStatus.ERROR, ApiError("unreadable_file", "We could not read those slides. Try a clearer photo or a PDF.")), s, {}, {}, {}) }
    @Test fun readingReady() = shot("20d-reading-ready") { s -> ReadingScreen(ReadingContract.State(LessonStatus.READY), s, {}, {}, {}) }

    @Test fun confirmSkills() = shot("21-confirm-skills") { s ->
        ConfirmSkillsScreen(ConfirmContract.State(false, Subject.MATH, skills.map { ConfirmContract.Row(it, chosenName = it.unsure?.candidates?.first() ?: it.name) }, manual = listOf("Tally marks")), s, {})
    }

    @Test fun calendar() = shot("22-calendar") { s ->
        CalendarScreen(CalendarContract.State(2026, 9, today, today, mapOf(today to CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH)), LocalDate(2026, 9, 10) to CalendarDay(LocalDate(2026, 9, 10), listOf(Subject.MATH))), listOf(CalendarContract.SkillLine(skills[0], Band.GOING_WELL))), s, {})
    }

    @Test fun progress() = shot("23-progress") { s ->
        ProgressScreen(ProgressContract.State(false, listOf(
            SkillReport("s1", "Counting by 2s", Subject.MATH, today, Band.GOING_WELL, "most of the time", 14, 0, null),
            SkillReport("s2", "The sh sound", Subject.ENGLISH, today, Band.GETTING_THERE, "more than half the time", 9, 0, null),
            SkillReport("s3", "Number bonds to 10", Subject.MATH, today, Band.NEEDS_ANOTHER_LOOK, "some of the time", 7, 0, LocalDate(2026, 9, 15)),
            SkillReport("s4", "Sight words", Subject.ENGLISH, today, null, null, 0, null, null),
        )), s)
    }
    @Test fun progressArabic() = shot("23b-progress-ar", Strings.ar) { s ->
        ProgressScreen(ProgressContract.State(false, listOf(SkillReport("s1", "Counting by 2s", Subject.MATH, today, Band.GOING_WELL, "most of the time", 14, 0, null))), s)
    }

    @Test fun settings() = shot("24-settings") { s -> SettingsScreen(SettingsContract.State(false, profile, ParentSettings(7, "en"), name = "Maya", grade = 1, curriculum = "IB PYP"), s, {}, {}) }
    @Test fun settingsArabic() = shot("24b-settings-ar", Strings.ar) { s -> SettingsScreen(SettingsContract.State(false, profile, ParentSettings(7, "ar"), name = "مايا", grade = 1, curriculum = "IB PYP"), s, {}, {}) }
}
