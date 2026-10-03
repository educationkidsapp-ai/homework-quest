package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlinx.datetime.LocalDate
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatTopic
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.ExamWindow
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.ReleasedResult
import quest.api.dto.StopCategory
import quest.api.dto.Subject
import quest.api.samples.HotSoupSeed
import quest.feature.chat.domain.Resolver
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationScreen
import quest.feature.content.domain.ChildResult
import quest.feature.journey.presentation.ExamResultContract
import quest.feature.journey.presentation.ExamResultScreen
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.LessonTheme
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerScreen
import quest.feature.map.presentation.MapContract
import quest.feature.map.presentation.WorldMapScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ProgressContract
import quest.feature.parent.presentation.ProgressScreen
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.FlagStore
import quest.feature.school.presentation.LocalFlags
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M4 — the screens the parent-flow fixes changed: the child's released result (card and screen, D4), "Continue exam"
 * (D9), "Closes at" in an exam (D8), presence and the resolved banner (D6, D7), and month-name dates with
 * direction-isolated comments and messages (D10, D12) — light, dark and Arabic.
 */
class ParentFlowFixesScreenshotTest {
    private val exams = object : FlagStore { override val flags = MutableStateFlow(mapOf("exams" to true)) }
    private val exam = HotSoupSeed.lesson.let { it.copy(title = "Autumn maths test", type = "exam", hintsOff = true, numbersOff = true, examPlay = it.plays[1]) }
    private val paper = exam.examPlay!!
    private val child = Child("c", "Hala", "sun", Curriculum.BRITISH, 1)
    private val now = 1_790_000_000_000L
    private val comment = "Good effort, Hala. Practise counting on from 2 and 4."
    private val marked = ChildResult("e2", "Autumn maths test", Subject.MATH, LocalDate(2026, 10, 3), "secure", comment, now)

    private fun child(name: String, strings: LessonStrings = LessonStrings.en, dark: Boolean = false, content: @Composable () -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark, LocalFlags provides exams) { LessonTheme(strings, rtl = strings === LessonStrings.ar) { content() } }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private fun parent(name: String, strings: Strings = Strings.en, dark: Boolean = false, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalDarkTheme provides dark) { ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } } }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    // ---- D4 + D9: the home cards
    private fun home(ar: Boolean = false) = MapContract.State(
        loading = false, child = if (ar) child.copy(name = "هلا") else child, now = now, exams = setOf("e1", "e2"),
        startedExams = setOf("e1"), marked = mapOf("e2" to marked),
        islands = listOf(
            Island("a", IslandKind.LESSON, LocalDate(2026, 10, 3), IslandState.TODAY, if (ar) "اختبار القراءة" else "Reading test", Subject.ENGLISH, "e1", examWindow = ExamWindow(now - 3_600_000, now + 40 * 60_000L)),
            Island("b", IslandKind.LESSON, LocalDate(2026, 10, 2), IslandState.DONE, if (ar) "اختبار الرياضيات" else "Autumn maths test", Subject.MATH, "e2"),
        ),
    )
    @Test fun homeReleasedAndContinue() = child("m4-01-home-result-continue") { WorldMapScreen(home(), {}, {}) }
    @Test fun homeReleasedAndContinueDark() = child("m4-01b-home-result-continue-dark", dark = true) { WorldMapScreen(home(), {}, {}) }
    @Test fun homeReleasedAndContinueArabic() = child("m4-01c-home-result-continue-ar", LessonStrings.ar) { WorldMapScreen(home(ar = true), {}, {}, strings = Strings.ar) }

    // ---- D4: the result screen — level and comment, no number (§7)
    private val result = ExamResultContract.State(loading = false, result = marked)
    @Test fun examResult() = child("m4-02-exam-result") { ExamResultScreen(result, {}, {}) }
    @Test fun examResultDark() = child("m4-02b-exam-result-dark", dark = true) { ExamResultScreen(result, {}, {}) }
    @Test fun examResultArabic() = child("m4-02c-exam-result-ar", LessonStrings.ar) { ExamResultScreen(result, {}, {}) }

    // ---- D8: "Closes at" on an exam question
    private val question = paper.stops.indexOfFirst { it.category == StopCategory.SINGLE }
    private val sitting = PlayerContract.State(phase = PlayerContract.Phase.STOP, lesson = exam, play = paper, index = question, childName = "Hala", exam = true, closesAt = now + 40 * 60_000L)
    @Test fun examClosesAt() = child("m4-03-exam-closes-at") { StopPlayerScreen(sitting, {}, {}) }
    @Test fun examClosesAtDark() = child("m4-03b-exam-closes-at-dark", dark = true) { StopPlayerScreen(sitting, {}, {}) }
    @Test fun examClosesAtArabic() = child("m4-03c-exam-closes-at-ar", LessonStrings.ar) { StopPlayerScreen(sitting, {}, {}) }

    // ---- D6 + D7 + D12: a manager's resolved complaint, staff offline, an English message in the Arabic app
    private fun conversation() = ChatConversationContract.State(
        childId = "c", teacherId = "nour", teacherName = "Ms. Nour", loading = false, staffRole = ChatStaffRole.MANAGERIAL,
        topic = ChatTopic.COMPLAINT, resolved = true, threadId = "t", peerOnline = false, resolver = Resolver.MANAGER,
        messages = listOf(
            ChatConversationContract.UiMessage("m1", "The bus was late three times this week.", true, now - 600_000, readAt = now - 500_000),
            ChatConversationContract.UiMessage("m2", "Thank you. We have spoken to the driver.", false, now - 300_000),
        ),
    )
    @Test fun resolvedByManager() = parent("m4-04-chat-resolved-manager") { s -> ChatConversationScreen(conversation(), s, {}, {}, {}, {}) }
    @Test fun resolvedByManagerDark() = parent("m4-04b-chat-resolved-manager-dark", dark = true) { s -> ChatConversationScreen(conversation(), s, {}, {}, {}, {}) }
    @Test fun resolvedByManagerArabic() = parent("m4-04c-chat-resolved-manager-ar", Strings.ar) { s -> ChatConversationScreen(conversation(), s, {}, {}, {}, {}) }

    // ---- D10 + D12: the parent's Progress — "3 October", and an English comment that keeps its full stop in Arabic
    private val progress = ProgressContract.State(
        loading = false, reports = emptyList(), streakDays = 1,
        results = listOf(ReleasedResult("e2", "Autumn maths test", LocalDate(2026, 10, 3), Subject.MATH, 60, "secure", comment, now)),
    )
    @Test fun progressDates() = parent("m4-05-progress-date") { s -> ProgressScreen(progress, s) }
    @Test fun progressDatesDark() = parent("m4-05b-progress-date-dark", dark = true) { s -> ProgressScreen(progress, s) }
    @Test fun progressDatesArabic() = parent("m4-05c-progress-date-ar", Strings.ar) { s -> ProgressScreen(progress, s) }
}
