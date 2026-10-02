package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.ExamWindow
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.StopCategory
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.feature.content.domain.SubmitOutcome
import quest.feature.journey.presentation.CompleteContract
import quest.feature.journey.presentation.JourneyContract
import quest.feature.journey.presentation.JourneyScreen
import quest.feature.journey.presentation.LessonCompleteScreen
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.parent.presentation.Strings
import quest.ui.design.LocalDarkTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M1d: every screen of an exam sitting — the home card in each of its states, the overview, a question, the one
 * acknowledgement, the submitted screen, a refused second sitting, answers still being sent, and a window that shut
 * mid-sitting — in light and dark, English and Arabic.
 */
class ExamScreensScreenshotTest {
    private val exam = HotSoupSeed.lesson.let { it.copy(title = "Reading exam · Unit 2", type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[1]) }
    private val paper = exam.examPlay!!
    private val child = Child("c", "Maya", "sun", Curriculum.BRITISH, 3)
    private val answered = paper.stops.take(2).associate { it.id to 0 }          // two answered: nothing shows how
    private val question = paper.stops.indexOfFirst { it.category == StopCategory.SINGLE && it.id !in answered }

    private fun shot(name: String, strings: LessonStrings = LessonStrings.en, dark: Boolean = false, content: @Composable () -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark) { LessonTheme(strings, rtl = strings === LessonStrings.ar) { content() } }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    // The home page was loaded at `now`: one exam is open for another two and a half hours, one was handed in, and one
    // arrived after its own window had shut — the teacher re-opened it for this student.
    private val now = 1_790_000_000_000L
    private fun home(ar: Boolean = false) = MapContract.State(
        loading = false, child = if (ar) child.copy(name = "نور") else child, now = now, exams = setOf("e1", "e2", "e3"),
        islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 10, 2), IslandState.TODAY, if (ar) "اختبار القراءة · الوحدة ٢" else exam.title, Subject.ENGLISH, "e1", examWindow = ExamWindow(now - 3_600_000, now + 150 * 60_000L)),
            Island("b", IslandKind.LESSON, LocalDate(2026, 10, 1), IslandState.DONE, if (ar) "اختبار الكسور" else "Fractions exam", Subject.MATH, "e2", starsEarned = 12, starsTotal = 27, examWindow = ExamWindow(now - 86_400_000, now + 3_600_000)),
            Island("c", IslandKind.LESSON, LocalDate(2026, 10, 1), IslandState.WAITING, if (ar) "اختبار العلوم" else "Science exam", Subject.SCIENCE, "e3", examWindow = ExamWindow(now - 86_400_000, now - 60_000)),   // re-opened by the teacher
            Island("d", IslandKind.LESSON, LocalDate(2026, 10, 2), IslandState.TODAY, if (ar) "العدّ بالاثنينات" else "Counting by 2s", Subject.MATH, "l2", 1, listOf(1)),
        ),
    )

    @Test fun examHome() = shot("40-exam-home") { WorldMapScreen(home(), {}, {}) }
    @Test fun examHomeDark() = shot("40b-exam-home-dark", dark = true) { WorldMapScreen(home(), {}, {}) }
    @Test fun examHomeArabic() = shot("40c-exam-home-ar", strings = LessonStrings.ar) { WorldMapScreen(home(ar = true), {}, {}, strings = Strings.ar) }

    private val overview = JourneyContract.State(loading = false, lesson = exam, play = paper, stopStars = answered, childName = "Maya", exam = true)
    @Test fun examOverview() = shot("41-exam-overview") { JourneyScreen(overview, {}, {}) }
    @Test fun examOverviewDark() = shot("41b-exam-overview-dark", dark = true) { JourneyScreen(overview, {}, {}) }
    @Test fun examOverviewArabic() = shot("41c-exam-overview-ar", strings = LessonStrings.ar) { JourneyScreen(overview, {}, {}) }

    private fun player(phase: PlayerContract.Phase, refusal: SubmitOutcome? = null) =
        PlayerContract.State(phase = phase, lesson = exam, play = paper, index = question, stopStars = answered, childName = "Maya", exam = true, refusal = refusal)
    @Test fun examQuestion() = shot("42-exam-question") { StopPlayerScreen(player(PlayerContract.Phase.STOP), {}, {}) }
    @Test fun examQuestionDark() = shot("42b-exam-question-dark", dark = true) { StopPlayerScreen(player(PlayerContract.Phase.STOP), {}, {}) }
    @Test fun examAnswerSaved() = shot("43-exam-answer-saved") { StopPlayerScreen(player(PlayerContract.Phase.STEP_DONE), {}, {}) }
    @Test fun examAnswerSavedDark() = shot("43b-exam-answer-saved-dark", dark = true) { StopPlayerScreen(player(PlayerContract.Phase.STEP_DONE), {}, {}) }
    @Test fun examAnswerSavedArabic() = shot("43c-exam-answer-saved-ar", strings = LessonStrings.ar) { StopPlayerScreen(player(PlayerContract.Phase.STEP_DONE), {}, {}) }

    private val submitted = CompleteContract.State(loading = false, lesson = exam, stars = 12, starsTotal = 27, childName = "Maya", exam = true)
    @Test fun examSubmitted() = shot("44-exam-submitted") { LessonCompleteScreen(submitted, {}, {}, {}, {}) }
    @Test fun examSubmittedDark() = shot("44b-exam-submitted-dark", dark = true) { LessonCompleteScreen(submitted, {}, {}, {}, {}) }
    @Test fun examSubmittedArabic() = shot("44c-exam-submitted-ar", strings = LessonStrings.ar) { LessonCompleteScreen(submitted, {}, {}, {}, {}) }

    @Test fun examAlreadyTaken() = shot("45-exam-already-taken") { StopPlayerScreen(player(PlayerContract.Phase.REFUSED, SubmitOutcome.ALREADY_TAKEN), {}, {}) }
    // Every question answered, not every answer delivered: "sending", never "submitted".
    @Test fun examSending() = shot("46-exam-sending") { StopPlayerScreen(player(PlayerContract.Phase.SENDING), {}, {}) }
    @Test fun examSendingArabicDark() = shot("46b-exam-sending-ar-dark", strings = LessonStrings.ar, dark = true) { StopPlayerScreen(player(PlayerContract.Phase.SENDING), {}, {}) }
    // The window shut mid-sitting: the submitted screen, saying so and what did not reach the teacher.
    @Test fun examClosedWithUndelivered() = shot("47-exam-closed-undelivered") { LessonCompleteScreen(submitted.copy(examClosed = true, undelivered = 3), {}, {}, {}, {}) }
    @Test fun examClosedWithUndeliveredArabic() = shot("47b-exam-closed-undelivered-ar", strings = LessonStrings.ar) { LessonCompleteScreen(submitted.copy(examClosed = true, undelivered = 1), {}, {}, {}, {}) }
}
