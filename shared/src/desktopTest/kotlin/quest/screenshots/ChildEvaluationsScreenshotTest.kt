package quest.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import quest.api.dto.ChatPeerRole
import quest.api.dto.EvaluationCriterion
import quest.api.dto.ParentEvaluation
import quest.api.dto.TeamLatestEvaluation
import quest.api.dto.TeamMember
import quest.feature.evaluations.presentation.ChildEvaluationsContract
import quest.feature.evaluations.presentation.ChildEvaluationsScreen
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.Strings
import quest.feature.team.presentation.ChildTeamContract
import quest.feature.team.presentation.ChildTeamScreen
import quest.ui.design.LocalDarkTheme
import quest.ui.design.ParentTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Feature 33 — **Teacher evaluations**, the page a parent reads: two rounds, newest first, a teacher per subject, the
 * four criteria as word chips (the whole scale is on the page, "Needs support" included, and none of it is a number),
 * then the comment, what is going well and the next step, with Reply privately under each.
 *
 * English and Arabic, light and dark. Copied to `docs/screenshots/feature-33/`.
 */
class ChildEvaluationsScreenshotTest {

    private fun shot(name: String, strings: Strings, dark: Boolean, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name, frames = 10) {
            CompositionLocalProvider(LocalDarkTheme provides dark) {
                ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } }
            }
        }
        assertTrue(f.length() > 1000, "screenshot $name is empty")
    }

    private fun criteria(participation: String, effort: String, behaviour: String, understanding: String) = listOf(
        EvaluationCriterion("participation", participation),
        EvaluationCriterion("effort", effort),
        EvaluationCriterion("behaviour", behaviour),
        EvaluationCriterion("understanding", understanding),
    )

    private fun state(ar: Boolean) = ChildEvaluationsContract.State(
        loading = false, childId = "c1",
        rows = listOf(
            ParentEvaluation(
                roundId = "rd-2", roundName = "Term 1 — mid-term", roundNameAr = "الفصل الأول — منتصف الفصل",
                subject = if (ar) "رياضيات" else "math",
                teacherId = "t-sara", teacherName = if (ar) "أ. سارة" else "Ms. Sara",
                criteria = criteria("excellent", "good", "excellent", "developing"),
                comment = if (ar) "تعدّ بالاثنينات دون مساعدة، وبدأت تدرك سبب ذلك." else "She counts in twos without help and is beginning to see why it works.",
                goingWell = if (ar) "العدّ التصاعدي، وشرح إجابتها بصوت عالٍ." else "Counting on, and explaining her answer out loud.",
                nextStep = if (ar) "الطرح بعد العشرة — سنتدرّب عليه بخط الأعداد." else "Taking away across ten — we will practise it with the number line.",
                releasedAt = "2026-10-18T09:00:00Z",
            ),
            ParentEvaluation(
                roundId = "rd-2", roundName = "Term 1 — mid-term", roundNameAr = "الفصل الأول — منتصف الفصل",
                subject = if (ar) "إنجليزي" else "english",
                teacherId = "t-noor", teacherName = if (ar) "أ. نور" else "Ms. Noor",
                criteria = criteria("good", "excellent", "excellent", "needsSupport"),
                comment = if (ar) "تقرأ بصوت واثق الآن وتساعد زميلتها." else "Maya reads aloud with real confidence now and helps the pair beside her.",
                goingWell = if (ar) "القراءة بصوت عالٍ، والإنصات لمن يقرأ." else "Reading aloud, and listening when somebody else reads.",
                nextStep = null,
                releasedAt = "2026-10-18T09:00:00Z",
            ),
            // An earlier round, and the one that carries only its criteria and a comment.
            ParentEvaluation(
                roundId = "rd-1", roundName = "Term 1 — start", roundNameAr = "الفصل الأول — البداية",
                subject = if (ar) "رياضيات" else "math",
                teacherId = "t-sara", teacherName = if (ar) "أ. سارة" else "Ms. Sara",
                criteria = criteria("good", "good", "excellent", "developing"),
                comment = if (ar) "بداية هادئة ومرحة للعام." else "A settled, cheerful start to the year.",
                goingWell = null, nextStep = null,
                releasedAt = "2026-09-20T09:00:00Z",
            ),
        ),
    )

    @Test fun evaluations() = shot("f33-01-evaluations", Strings.en, dark = false) { s -> ChildEvaluationsScreen(state(false), s) }
    @Test fun evaluationsDark() = shot("f33-01b-evaluations-dark", Strings.en, dark = true) { s -> ChildEvaluationsScreen(state(false), s) }
    @Test fun evaluationsArabic() = shot("f33-01c-evaluations-ar", Strings.ar, dark = false) { s -> ChildEvaluationsScreen(state(true), s) }
    @Test fun evaluationsArabicDark() = shot("f33-01d-evaluations-ar-dark", Strings.ar, dark = true) { s -> ChildEvaluationsScreen(state(true), s) }

    /** Feature 32's team page with feature 33's entry point on it: "Latest evaluation" under each teacher's row. */
    private fun team(ar: Boolean) = ChildTeamContract.State(
        loading = false, childId = "c1",
        members = listOf(
            TeamMember(
                staffId = "t-sara", name = if (ar) "أ. سارة" else "Ms. Sara", role = ChatPeerRole.TEACHER, subjects = "math",
                latestEvaluation = TeamLatestEvaluation("Term 1 — mid-term", "الفصل الأول — منتصف الفصل", if (ar) "رياضيات" else "math"),
            ),
            TeamMember(
                staffId = "t-noor", name = if (ar) "أ. نور" else "Ms. Noor", role = ChatPeerRole.TEACHER, subjects = "english",
                latestEvaluation = TeamLatestEvaluation("Term 1 — mid-term", "الفصل الأول — منتصف الفصل", if (ar) "إنجليزي" else "english"),
            ),
            // A coordinator writes no evaluation, so her row carries no link at all.
            TeamMember(staffId = "co-lina", name = if (ar) "أ. لينا" else "Ms. Lina", role = ChatPeerRole.COORDINATOR, subjects = "math"),
        ),
    )

    @Test fun teamWithLatestEvaluation() = shot("f33-03-team-latest", Strings.en, dark = false) { s -> ChildTeamScreen(team(false), s) }
    @Test fun teamWithLatestEvaluationArabic() = shot("f33-03b-team-latest-ar", Strings.ar, dark = false) { s -> ChildTeamScreen(team(true), s) }

    /** Nothing released yet: one line, in her language, and no empty headings. */
    @Test fun evaluationsEmpty() = shot("f33-02-evaluations-empty", Strings.en, dark = false) { s ->
        ChildEvaluationsScreen(ChildEvaluationsContract.State(loading = false, childId = "c1"), s)
    }
}
