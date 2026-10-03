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
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.Subject
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentHomeContract
import quest.feature.parent.presentation.ParentHomeScreen
import quest.feature.parent.presentation.Strings
import quest.feature.push.presentation.PushPermissionCardContent
import quest.feature.push.presentation.PushStrings
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M5 — the parent home's "Turn on notifications" card, and the exam sitting's countdown (40 minutes left, and the last
 * five minutes in the warning colours), in light, dark and Arabic. Copied to `docs/screenshots/push-and-exam-timer/`.
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

    // ---- push: the card on the parent home, asked in context after sign-in
    private val hala = Child("c1", "Hala", "sun", Curriculum.BRITISH, 1)
    private val today = LocalDate(2026, 10, 3)
    private fun parent(name: String, strings: Strings = Strings.en, dark: Boolean = false) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark) {
                ParentTheme(rtl = strings.isRtl) {
                    CompositionLocalProvider(LocalStrings provides strings) {
                        val home = ParentHomeContract.State(false, listOf(hala), hala, listOf(CalendarDay(today, listOf(Subject.MATH), listOf("l1"), emptyList())))
                        ParentHomeScreen(home, strings, {}, {}, {}, {}, {}, banner = {
                            PushPermissionCardContent(if (strings.isRtl) PushStrings.ar else PushStrings.en, Modifier.padding(top = 10.dp), onTurnOn = {}, onNotNow = {})
                        })
                    }
                }
            }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    @Test fun pushCard() = parent("m5-03-push-permission-card")
    @Test fun pushCardDark() = parent("m5-03b-push-permission-card-dark", dark = true)
    @Test fun pushCardArabic() = parent("m5-03c-push-permission-card-ar", Strings.ar)
}
