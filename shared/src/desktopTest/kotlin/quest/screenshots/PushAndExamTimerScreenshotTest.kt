package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.coroutines.flow.MutableStateFlow
import quest.api.dto.StopCategory
import quest.api.samples.HotSoupSeed
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerScreen
import quest.feature.school.domain.FlagStore
import quest.feature.school.presentation.LocalFlags
import quest.ui.design.LocalDarkTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M5 — the exam sitting's countdown (40 minutes left, and the last five minutes in the warning colours) in light, dark
 * and Arabic. Copied to `docs/screenshots/push-and-exam-timer/`.
 */
class PushAndExamTimerScreenshotTest {
    private val exams = object : FlagStore { override val flags = MutableStateFlow(mapOf("exams" to true)) }
    private val exam = HotSoupSeed.lesson.let { it.copy(title = "Autumn maths test", type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[1]) }
    private val paper = exam.examPlay!!
    private val closes = 1_790_000_000_000L

    private fun child(name: String, strings: LessonStrings = LessonStrings.en, dark: Boolean = false, content: @Composable () -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark, LocalFlags provides exams) { LessonTheme(strings, rtl = strings === LessonStrings.ar) { content() } }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private val question = paper.stops.indexOfFirst { it.category == StopCategory.SINGLE }
    private val sitting = PlayerContract.State(phase = PlayerContract.Phase.STOP, lesson = exam, play = paper, index = question, childName = "Hala", exam = true, closesAt = closes)
    private val fortyLeft = closes - (40 * 60_000L + 12_000)
    private val fourLeft = closes - (4 * 60_000L + 37_000)

    @Test fun countdown() = child("m5-01-exam-countdown") { StopPlayerScreen(sitting, {}, {}, now = { fortyLeft }) }
    @Test fun countdownDark() = child("m5-01b-exam-countdown-dark", dark = true) { StopPlayerScreen(sitting, {}, {}, now = { fortyLeft }) }
    @Test fun countdownArabic() = child("m5-01c-exam-countdown-ar", LessonStrings.ar) { StopPlayerScreen(sitting, {}, {}, now = { fortyLeft }) }
    @Test fun lastFiveMinutes() = child("m5-02-exam-countdown-last-five") { StopPlayerScreen(sitting, {}, {}, now = { fourLeft }) }
    @Test fun lastFiveMinutesDark() = child("m5-02b-exam-countdown-last-five-dark", dark = true) { StopPlayerScreen(sitting, {}, {}, now = { fourLeft }) }
    @Test fun lastFiveMinutesArabic() = child("m5-02c-exam-countdown-last-five-ar", LessonStrings.ar) { StopPlayerScreen(sitting, {}, {}, now = { fourLeft }) }
}
