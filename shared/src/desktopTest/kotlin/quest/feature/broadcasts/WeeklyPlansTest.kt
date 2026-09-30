package quest.feature.broadcasts

import kotlinx.datetime.LocalDate
import quest.api.dto.BroadcastAttachment
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.ChatStaffRole
import quest.api.dto.Curriculum
import quest.api.dto.WeeklyPlanArchive
import quest.api.dto.WeeklyPlanEntry
import quest.api.dto.WeeklyPlanWeek
import quest.feature.broadcasts.domain.isImage
import quest.feature.broadcasts.domain.isWebUrl
import quest.feature.broadcasts.domain.weeklyPlans
import quest.feature.broadcasts.presentation.planDescription
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MH3: the weekly-plan archive as the page groups it, and which attachment the page will try to draw. */
class WeeklyPlansTest {

    private val monday = LocalDate(2026, 9, 28)

    private fun plan(week: String, grade: Int?, read: Boolean = false) = BroadcastView(
        id = "p-$week-$grade", kind = BroadcastKind.WEEKLY_PLAN, authorId = "mg", authorName = "Ms. Nour",
        authorRole = ChatStaffRole.MANAGERIAL, weekStart = week, grade = grade, bodyEn = "Plan",
        curriculum = Curriculum.BRITISH, createdAt = 1_759_000_000_000L, read = read,
        attachment = BroadcastAttachment("/media/attachments/a-$week", "plan.png", "a-$week", "image/png"),
    )

    private fun archive(vararg weeks: Pair<String, List<BroadcastView>>) = WeeklyPlanArchive(
        from = weeks.last().first, to = weeks.first().first, unread = 0,
        weeks = weeks.map { (week, plans) -> WeeklyPlanWeek(week, plans.map { WeeklyPlanEntry(it) }) },
    )

    // ---- 1. this week is pinned, every other week is collapsed below it

    @Test fun thisWeeksPlanIsPinnedAndTheEarlierWeeksFollow() {
        val plans = weeklyPlans(
            archive(
                "2026-09-27" to listOf(plan("2026-09-27", 1)),
                "2026-09-20" to listOf(plan("2026-09-20", 1)),
                "2026-09-13" to listOf(plan("2026-09-13", 1)),
            ),
            today = monday, grade = 1,
        )
        assertEquals("p-2026-09-27-1", plans.current?.id)
        assertEquals(listOf("2026-09-20", "2026-09-13"), plans.earlier.map { it.weekStart })
        assertFalse(plans.isEmpty)
    }

    /** The grade's plan wins over a department-wide one an older server may still be serving for the same week. */
    @Test fun theChildsGradeDecidesWhichOfTheWeeksPlansIsPinned() {
        val plans = weeklyPlans(
            archive("2026-09-27" to listOf(plan("2026-09-27", null), plan("2026-09-27", 1))),
            today = monday, grade = 1,
        )
        assertEquals("p-2026-09-27-1", plans.current?.id)
        // The one that was not pinned is still readable, under its own week.
        assertEquals(listOf("p-2026-09-27-null"), plans.earlier.single().plans.map { it.id })
    }

    /** No plan for the week the parent is in: nothing is pinned, and last week is not promoted into its place. */
    @Test fun aWeekWithNoPlanPinsNothing() {
        val plans = weeklyPlans(archive("2026-09-20" to listOf(plan("2026-09-20", 1))), monday, 1)
        assertNull(plans.current)
        assertEquals(listOf("2026-09-20"), plans.earlier.map { it.weekStart })
    }

    /**
     * #171's Saturday rule, which arrived on the announcements feed and moved here with the plans: on the eve of the
     * school week the plan for the week about to start is the one pinned, and this week's is the fallback.
     */
    @Test fun onSaturdayNextWeeksPlanIsPinnedWhenItExists() {
        val saturday = LocalDate(2026, 9, 26)
        val withNext = weeklyPlans(
            archive("2026-09-27" to listOf(plan("2026-09-27", 1)), "2026-09-20" to listOf(plan("2026-09-20", 1))),
            today = saturday, grade = 1,
        )
        assertEquals("p-2026-09-27-1", withNext.current?.id)
        assertEquals(listOf("2026-09-20"), withNext.earlier.map { it.weekStart })

        val withoutNext = weeklyPlans(archive("2026-09-20" to listOf(plan("2026-09-20", 1))), saturday, 1)
        assertEquals("p-2026-09-20-1", withoutNext.current?.id)
        assertTrue(withoutNext.earlier.isEmpty())
    }

    @Test fun anEmptyArchiveIsEmpty() {
        assertTrue(weeklyPlans(WeeklyPlanArchive("2026-09-13", "2026-09-27"), monday, 1).isEmpty)
    }

    // ---- 2. what the page will try to draw

    @Test fun onlyAnImageAttachmentIsDrawn() {
        assertTrue(BroadcastAttachment("/media/attachments/a", "plan.png", "a", "image/png").isImage)
        assertTrue(BroadcastAttachment("/media/attachments/a", "plan.webp", "a", "image/webp").isImage)
        assertFalse(BroadcastAttachment("/media/attachments/a", "plan.pdf", "a", "application/pdf").isImage)
        // An MH1 row with no stored type falls back to the extension.
        assertTrue(BroadcastAttachment("/media/attachments/a", "plan.JPG", "a").isImage)
        assertFalse(BroadcastAttachment("/media/attachments/a", "plan.pdf", "a").isImage)
        assertFalse(BroadcastAttachment("/media/attachments/a", null, "a").isImage)
    }

    /**
     * **A row with no id is never drawn, whatever it is called.** Before MH1 an attachment was a URL the composer
     * typed: there is no `attachments` row to fetch with the parent's bearer and no cache key for it, so a
     * `https://…/plan.png` taking the image branch would download nothing and leave a permanent *Try again* where
     * #171 draws a tappable link. The card tests [isWebUrl] first for the same reason.
     */
    @Test fun aPreMh1RowIsALinkAndNotAnImage() {
        val typed = BroadcastAttachment("https://school.test/plan.png", "plan.png")
        assertFalse(typed.isImage, "nothing here is ours to fetch")
        assertTrue(typed.isWebUrl, "so #171's chip keeps it")

        // An `http` one too, and case does not matter.
        assertTrue(BroadcastAttachment("HTTP://school.test/plan.jpg", "plan.jpg").isWebUrl)
        // An MH1 attachment is root-relative, so it is never mistaken for somebody else's page.
        assertFalse(BroadcastAttachment("/media/attachments/a", "plan.png", "a", "image/png").isWebUrl)
        // A blank id is no id.
        assertFalse(BroadcastAttachment("/media/attachments/a", "plan.png", "  ", "image/png").isImage)
    }

    // ---- 3. a11y: the image says what it is, which grade it is for and which week

    @Test fun theImageDescriptionNamesTheGradeAndTheWeek() {
        assertEquals(
            "Weekly plan, Grade 1, Week of 27 September",
            planDescription(plan("2026-09-27", 1), childGrade = 3, strings = Strings.en),
        )
        // A plan with no grade of its own falls back to the child's, which is the grade the parent is looking at.
        assertEquals(
            "Weekly plan, Grade 3, Week of 27 September",
            planDescription(plan("2026-09-27", null), childGrade = 3, strings = Strings.en),
        )
        assertEquals(
            "الخطة الأسبوعية, الصف 1, أسبوع 27 سبتمبر",
            planDescription(plan("2026-09-27", 1), childGrade = 1, strings = Strings.ar),
        )
    }
}
