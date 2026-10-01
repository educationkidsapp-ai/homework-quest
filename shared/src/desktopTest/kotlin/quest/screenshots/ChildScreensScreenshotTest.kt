package quest.screenshots

import quest.ui.design.LocalDarkTheme
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import quest.ui.design.DashboardTokens
import quest.ui.design.DashboardCard
import quest.feature.journey.presentation.LessonTheme
import quest.feature.journey.presentation.LessonStrings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Stop
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.api.samples.MathSeed
import quest.api.samples.PhonicsSeed
import quest.feature.content.data.FakeContentApi
import quest.feature.journey.presentation.CompleteContract
import quest.feature.journey.presentation.JourneyContract
import quest.feature.journey.presentation.JourneyScreen
import quest.feature.journey.presentation.LessonCompleteScreen
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.SchoolBranding
import quest.feature.school.presentation.LocalSchoolBranding
import quest.ui.design.Dimens
import quest.ui.design.LocalThemeOverrides
import quest.ui.design.ThemeOverrides
import quest.ui.design.schoolThemeOverrides
import quest.ui.stops.StopContent
import kotlin.test.Test
import kotlin.test.assertTrue

/** One screenshot per child screen and per stop type (dev prompt §10). Files land in shared/build/screenshots. */
class ChildScreensScreenshotTest {
    private val hot = HotSoupSeed.lesson
    private val child = Child("c", "Maya", "sun", Curriculum.BRITISH, 1)

    private fun shot(name: String, overrides: ThemeOverrides = ThemeOverrides(), branding: SchoolBranding = SchoolBranding(), strings: LessonStrings = LessonStrings.en, dark: Boolean = false, content: @Composable () -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalThemeOverrides provides overrides, LocalSchoolBranding provides branding, LocalDarkTheme provides dark) { LessonTheme(strings, rtl = strings === LessonStrings.ar) { content() } }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private fun stopShot(name: String, stop: Stop) = shot(name) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Dimens.s24)) {
            DashboardCard(Modifier.padding(horizontal = Dimens.s16)) { Text(stop.speak, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.inkStrong) }
            Box(Modifier.padding(top = Dimens.s16)) { StopContent(stop, onEvent = {}) }
        }
    }

    @Test fun worldMap() = shot("02-world-map") {
        WorldMapScreen(MapContract.State(loading = false, child = child, streakDays = 2, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "The sh sound", Subject.ENGLISH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("r", IslandKind.REVIEW, LocalDate(2026, 9, 14), IslandState.TODAY, "The sh sound", skillId = "sh", playId = "p"),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.WAITING, "Hot Soup for Mummy · Part 1", Subject.ENGLISH, "l3", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Still asleep"),
        )), {}, {})
    }
    /**
     * §3 white label: the same student home under Al Noor's theme — its accent on the buttons, the chips and the
     * avatar, its ground behind the cards.
     */
    @Test fun worldMapThemed() = shot(
        "02c-world-map-themed",
        overrides = schoolThemeOverrides(FakeContentApi.alNoorTheme),
        branding = SchoolBranding(appName = "Al Noor Quest", schoolName = "Al Noor School"),
    ) {
        WorldMapScreen(MapContract.State(loading = false, child = child, streakDays = 2, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "The sh sound", Subject.ENGLISH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.WAITING, "Hot Soup for Mummy · Part 1", Subject.ENGLISH, "l3", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Still asleep"),
        )), {}, {})
    }

    @Test fun worldMapEmpty() = shot("02b-world-map-empty") { WorldMapScreen(MapContract.State(loading = false, child = child, islands = listOf(Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Still asleep"))), {}, {}) }

    @Test fun worldMapFormalGrade5() = shot("02d-world-map-formal-grade5") {
        WorldMapScreen(MapContract.State(loading = false, child = child.copy(grade = 5), streakDays = 4, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "Fractions & Decimals", Subject.MATH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Cellular Biology", Subject.SCIENCE, "l2", 1, listOf(1)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.WAITING, "Shakespeare & Sonnets", Subject.ENGLISH, "l3", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Grammaire Française", Subject.FRENCH),
        )), {}, {})
    }

    /**
     * MH4: the same student home in Arabic. Everything the screen writes comes from [Strings], and `AcademicTheme`
     * takes the layout direction with it — the avatar, the streak pill and the Open button all mirror.
     */
    @Test fun worldMapFormalArabic() = shot("02e-world-map-formal-ar") {
        WorldMapScreen(MapContract.State(loading = false, child = child.copy(name = "نور"), streakDays = 3, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "صوت الشين", Subject.ENGLISH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "العدّ بالاثنينات", Subject.MATH, "l2", 1, listOf(1)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.WAITING, "حساء ساخن لأمي", Subject.ENGLISH, "l3", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "ما زال نائماً"),
        )), {}, {}, strings = Strings.ar)
    }

    @Test fun journey() = shot("03-journey") {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1), stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3), childName = "Maya"), {}, {})
    }
    @Test fun journeyComplete() = shot("03b-journey-full") {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1, 2), completedLevels = listOf(1), stopStars = hot.plays[0].stops.associate { it.id to 3 }, childName = "Maya"), {}, {})
    }

    // §8: an exam is one play, no level selector, no stars, under an Exam badge.
    private val exam = hot.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = hot.plays[0])
    @Test fun examOverview() = shot("03c-exam-overview") {
        JourneyScreen(JourneyContract.State(loading = false, lesson = exam, play = exam.examPlay, stopStars = mapOf("hs1-move" to 3), childName = "Maya", exam = true), {}, {})
    }
    @Test fun journeyArabic() = shot("03d-journey-ar", strings = LessonStrings.ar) {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1), stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3), childName = "Maya"), {}, {})
    }

    private val l1 = hot.plays[0].stops
    @Test fun stopMove() = stopShot("04-stop-move", l1[0])
    @Test fun stopStoryPieces() = stopShot("05-stop-story-pieces", l1[1])
    @Test fun stopReadPage() = stopShot("06-stop-read-page", l1[2])
    @Test fun stopReadPageTap() = stopShot("06b-stop-read-page-tap", l1[3])
    @Test fun stopWordCards() = stopShot("07-stop-word-cards", l1[5])
    @Test fun stopMatch() = stopShot("08-stop-match", l1[6])
    @Test fun stopOrder() = stopShot("09-stop-order", l1[7])
    @Test fun stopExitTicket() = stopShot("10-stop-exit-ticket", l1[8])
    @Test fun stopChoice() = stopShot("11-stop-choice", hot.plays[1].stops[1])
    @Test fun stopMultiSelect() = stopShot("12-stop-multi-select", hot.plays[1].stops[2])
    @Test fun stopTrueFalse() = stopShot("13-stop-true-false", hot.plays[1].stops[3])
    @Test fun stopWriteSentence() = stopShot("14-stop-write-sentence", hot.plays[1].stops[6])
    @Test fun stopRetell() = stopShot("15-stop-retell", hot.plays[2].stops[1])
    @Test fun stopOpenAnswer() = stopShot("16-stop-open-answer", hot.plays[2].stops[2])
    @Test fun stopWriteFree() = stopShot("16b-stop-write-free", hot.plays[2].stops[3])
    @Test fun stopExplain() = stopShot("17-stop-explain", MathSeed.level1.stops[0])
    @Test fun stopSequence() = stopShot("18-stop-sequence", MathSeed.level1.stops[1])
    @Test fun stopCount() = stopShot("19-stop-count", MathSeed.level1.stops[2])
    @Test fun stopCompare() = stopShot("20-stop-compare", MathSeed.level1.stops[4])
    @Test fun stopSound() = stopShot("21-stop-sound", PhonicsSeed.level1.stops[1])
    @Test fun stopWord() = stopShot("22-stop-word", PhonicsSeed.level1.stops[2])
    @Test fun stopReadTap() = stopShot("23-stop-read-tap", PhonicsSeed.level1.stops[3])
    @Test fun stopTrace() = stopShot("24-stop-trace", PhonicsSeed.level1.stops[5])
    @Test fun stopSelectAll() = stopShot("25-stop-select-all", PhonicsSeed.level2.stops[5])

    private fun playerState(phase: PlayerContract.Phase) = PlayerContract.State(phase = phase, lesson = hot, play = hot.plays[0], index = 3, stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3, "hs1-page1" to 3),
        hint = "Who is in bed on page 1?", praise = "Correct", childName = "Maya")
    @Test fun player() = shot("26-player") { StopPlayerScreen(playerState(PlayerContract.Phase.STOP), {}, {}) }
    @Test fun hintSheet() = shot("27-hint-sheet") { StopPlayerScreen(playerState(PlayerContract.Phase.HINT).copy(index = 8, numberLine = MathSeed.level1.stops.filterIsInstance<Stop.Sequence>().first().numberLine), {}, {}) }
    @Test fun correctOverlay() = shot("28-correct-overlay") { StopPlayerScreen(playerState(PlayerContract.Phase.CORRECT), {}, {}) }
    @Test fun stepDone() = shot("29-step-done") { StopPlayerScreen(playerState(PlayerContract.Phase.STEP_DONE), {}, {}) }
    @Test fun examSitting() = shot("26b-exam-sitting") { StopPlayerScreen(playerState(PlayerContract.Phase.STOP).copy(lesson = exam, exam = true, index = 8), {}, {}) }
    @Test fun examAnswerSaved() = shot("28b-exam-answer-saved") { StopPlayerScreen(playerState(PlayerContract.Phase.CORRECT).copy(lesson = exam, exam = true, praise = "Answer saved"), {}, {}) }
    @Test fun playerArabic() = shot("26c-player-ar", strings = LessonStrings.ar) { StopPlayerScreen(playerState(PlayerContract.Phase.STOP).copy(index = 8), {}, {}) }

    @Test fun lessonComplete() = shot("30-lesson-complete") { LessonCompleteScreen(CompleteContract.State(loading = false, lesson = hot, stars = 24, starsTotal = 27, childName = "Maya", nextLevelUnlocked = true), {}, {}, {}, {}) }
    @Test fun examResult() = shot("31-exam-result") { LessonCompleteScreen(CompleteContract.State(loading = false, lesson = exam, stars = 24, starsTotal = 27, childName = "Maya", exam = true), {}, {}, {}, {}) }

    @Test fun worldMapWithExam() = shot("02f-world-map-exam") {
        WorldMapScreen(MapContract.State(loading = false, child = child, islands = listOf(
            Island("e", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Unit 1 exam", Subject.MATH, "l9", 1, listOf(1), examWindow = quest.api.dto.ExamWindow(opensAt = 0, closesAt = 1)),
            Island("d", IslandKind.LESSON, LocalDate(2026, 9, 12), IslandState.DONE, "Reading exam", Subject.ENGLISH, "l8", 1, listOf(1), listOf(1), 18, 21, examWindow = quest.api.dto.ExamWindow(opensAt = 0, closesAt = 1)),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
        )), {}, {})
    }

    // ---- dark palette: the same screens under `LocalDarkTheme` ----------------------------------------------------
    @Test fun worldMapDark() = shot("02g-world-map-dark", dark = true) {
        WorldMapScreen(MapContract.State(loading = false, child = child, streakDays = 2, islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 9, 11), IslandState.DONE, "The sh sound", Subject.ENGLISH, "l1", 1, listOf(1, 2), listOf(1), 18, 21),
            Island("e", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Unit 1 exam", Subject.MATH, "l9", 1, listOf(1), examWindow = quest.api.dto.ExamWindow(opensAt = 0, closesAt = 1)),
            Island("b", IslandKind.LESSON, LocalDate(2026, 9, 14), IslandState.TODAY, "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
            Island("locked", IslandKind.LOCKED, LocalDate(2026, 9, 15), IslandState.LOCKED, "Fractions"),
        )), {}, {})
    }
    @Test fun journeyDark() = shot("03e-journey-dark", dark = true) {
        JourneyScreen(JourneyContract.State(loading = false, lesson = hot, play = hot.plays[0], levelsUnlocked = listOf(1), stopStars = mapOf("hs1-move" to 3, "hs1-pieces" to 3), childName = "Maya"), {}, {})
    }
    @Test fun playerDark() = shot("26d-player-dark", dark = true) { StopPlayerScreen(playerState(PlayerContract.Phase.STOP).copy(index = 8), {}, {}) }
    @Test fun hintSheetDark() = shot("27b-hint-sheet-dark", dark = true) { StopPlayerScreen(playerState(PlayerContract.Phase.HINT).copy(index = 8, numberLine = MathSeed.level1.stops.filterIsInstance<Stop.Sequence>().first().numberLine), {}, {}) }
    @Test fun examSittingDark() = shot("26e-exam-sitting-dark", dark = true) { StopPlayerScreen(playerState(PlayerContract.Phase.STOP).copy(lesson = exam, exam = true, index = 8), {}, {}) }
    @Test fun lessonCompleteDark() = shot("30b-lesson-complete-dark", dark = true) { LessonCompleteScreen(CompleteContract.State(loading = false, lesson = hot, stars = 24, starsTotal = 27, childName = "Maya", nextLevelUnlocked = true), {}, {}, {}, {}) }
    @Test fun stopMatchDark() = shot("08b-stop-match-dark", dark = true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = Dimens.s24)) { StopContent(l1[6], onEvent = {}) }
    }
}
