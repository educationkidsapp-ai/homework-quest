package quest.feature.broadcasts

import kotlinx.datetime.LocalDate
import quest.api.dto.BroadcastAttachment
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.ChatStaffRole
import quest.api.dto.Curriculum
import quest.feature.broadcasts.domain.broadcastBody
import quest.feature.broadcasts.domain.groupBroadcasts
import quest.feature.broadcasts.domain.unreadAnnouncements
import quest.feature.broadcasts.domain.weekStartOf
import quest.feature.broadcasts.presentation.authorLine
import quest.feature.broadcasts.presentation.broadcastDescription
import quest.feature.broadcasts.presentation.weekLabel
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RM4, narrowed by MH3: everything about the **Announcements** feed that is a function rather than a screen — which
 * rows are hidden, what the author line says, and which body the parent reads. The plans are [WeeklyPlansTest]'s.
 */
class BroadcastsFeedTest {

    private val monday = LocalDate(2026, 9, 28)
    private val sunday = LocalDate(2026, 9, 27)
    private val now = 1_759_000_000_000L

    private fun row(
        id: String,
        kind: BroadcastKind,
        weekStart: String? = null,
        role: ChatStaffRole = ChatStaffRole.MANAGERIAL,
        subject: String? = null,
        curriculum: Curriculum? = null,
        bodyAr: String? = null,
        expiresAt: Long? = null,
        read: Boolean = false,
    ) = BroadcastView(
        id = id, kind = kind, authorId = "a-$id", authorName = "Ms. Nour", authorRole = role,
        title = "T-$id", bodyEn = "Body $id", bodyAr = bodyAr, weekStart = weekStart, curriculum = curriculum,
        subject = subject, expiresAt = expiresAt, createdAt = now - 1, read = read,
    )

    // ---- 1. the week a plan belongs to: the server snaps to Sunday, and so does the app

    @Test fun aWeekStartsOnSunday() {
        assertEquals(sunday, weekStartOf(sunday))
        assertEquals(sunday, weekStartOf(monday))
        assertEquals(sunday, weekStartOf(LocalDate(2026, 10, 3)))
        assertEquals(LocalDate(2026, 10, 4), weekStartOf(LocalDate(2026, 10, 4)))
    }

    // ---- 2. MH3: grouping by kind, and the plans the server still sends are not this page's

    @Test fun everyWeeklyPlanIsDroppedAndTheRestIsGroupedByKind() {
        val groups = groupBroadcasts(
            listOf(
                row("plan-now", BroadcastKind.WEEKLY_PLAN, weekStart = "2026-09-27"),
                row("plan-old", BroadcastKind.WEEKLY_PLAN, weekStart = "2026-09-20"),
                row("ann", BroadcastKind.ANNOUNCEMENT),
                row("evt", BroadcastKind.EVENT),
            ),
            nowMillis = now,
        )
        assertEquals(listOf("ann"), groups.announcements.map { it.id })
        assertEquals(listOf("evt"), groups.events.map { it.id })
        assertTrue(!groups.isEmpty)
    }

    /** A feed of nothing but plans is an empty Announcements page, not a page with a plan on it. */
    @Test fun aFeedOfPlansOnlyIsEmpty() {
        assertTrue(groupBroadcasts(listOf(row("p", BroadcastKind.WEEKLY_PLAN, weekStart = "2026-09-27")), now).isEmpty)
    }

    @Test fun anEmptyFeedIsEmpty() {
        assertTrue(groupBroadcasts(emptyList(), now).isEmpty)
    }

    /**
     * The badge beside *Announcements* on the parent home. `BroadcastFeed.unread` counts the plans too — those are the
     * Weekly plan badge's — and an expired row is nobody's.
     */
    @Test fun theAnnouncementsBadgeCountsNeitherPlansNorExpiredRowsNorReadOnes() {
        val items = listOf(
            row("plan", BroadcastKind.WEEKLY_PLAN, weekStart = "2026-09-27"),
            row("ann", BroadcastKind.ANNOUNCEMENT),
            row("evt", BroadcastKind.EVENT),
            row("gone", BroadcastKind.EVENT, expiresAt = now - 1),
            row("seen", BroadcastKind.ANNOUNCEMENT, read = true),
        )
        assertEquals(2, unreadAnnouncements(items, now))
        assertEquals(0, unreadAnnouncements(emptyList(), now))
    }

    // ---- 3. expiry: the server drops expired rows, and the app drops them again against its own clock

    @Test fun anExpiredRowIsHiddenAndAFutureOneIsNot() {
        val groups = groupBroadcasts(
            listOf(
                row("gone", BroadcastKind.EVENT, expiresAt = now - 1),
                row("live", BroadcastKind.EVENT, expiresAt = now + 1),
                row("forever", BroadcastKind.EVENT, expiresAt = null),
            ),
            now,
        )
        assertEquals(listOf("live", "forever"), groups.events.map { it.id })
    }

    // ---- 4. the author line: a coordinator speaks for a subject, a manager for a department

    @Test fun theAuthorLineNamesTheRole() {
        assertEquals(
            "From your Math coordinator",
            authorLine(row("a", BroadcastKind.ANNOUNCEMENT, role = ChatStaffRole.COORDINATOR, subject = "math"), Strings.en),
        )
        assertEquals(
            "From the British department manager",
            authorLine(row("b", BroadcastKind.WEEKLY_PLAN, curriculum = Curriculum.BRITISH), Strings.en),
        )
        assertEquals(
            "From the American department manager",
            authorLine(row("c", BroadcastKind.EVENT, curriculum = Curriculum.AMERICAN), Strings.en),
        )
        assertEquals(
            "من منسّق رياضيات",
            authorLine(row("d", BroadcastKind.ANNOUNCEMENT, role = ChatStaffRole.COORDINATOR, subject = "math"), Strings.ar),
        )
        assertEquals(
            "من مدير قسم بريطاني",
            authorLine(row("e", BroadcastKind.WEEKLY_PLAN, curriculum = Curriculum.BRITISH), Strings.ar),
        )
    }

    @Test fun aCoordinatorWithSeveralSubjectsListsThemAndAnUnknownKeyPassesThrough() {
        assertEquals(
            "From your English, Science coordinator",
            authorLine(row("a", BroadcastKind.ANNOUNCEMENT, role = ChatStaffRole.COORDINATOR, subject = "english, science"), Strings.en),
        )
        assertEquals(
            "From your drama coordinator",
            authorLine(row("b", BroadcastKind.ANNOUNCEMENT, role = ChatStaffRole.COORDINATOR, subject = "drama"), Strings.en),
        )
    }

    /** Nothing may leave the card without an author: a row whose role carries no scope is named by its author. */
    @Test fun aRowWithNoSubjectOrDepartmentNamesTheAuthor() {
        assertEquals("From Ms. Nour", authorLine(row("a", BroadcastKind.ANNOUNCEMENT, role = ChatStaffRole.COORDINATOR), Strings.en))
        assertEquals("From Ms. Nour", authorLine(row("b", BroadcastKind.WEEKLY_PLAN, curriculum = null), Strings.en))
        assertEquals("From Ms. Nour", authorLine(row("c", BroadcastKind.EVENT, role = ChatStaffRole.TEACHER), Strings.en))
    }

    // ---- 5. the body: Arabic when the school wrote it, English when it did not

    @Test fun arabicFallsBackToEnglish() {
        val both = row("a", BroadcastKind.ANNOUNCEMENT, bodyAr = "نص عربي")
        assertEquals("نص عربي", broadcastBody(both, arabic = true))
        assertEquals("Body a", broadcastBody(both, arabic = false))

        val englishOnly = row("b", BroadcastKind.ANNOUNCEMENT, bodyAr = null)
        assertEquals("Body b", broadcastBody(englishOnly, arabic = true))

        // A blank `bodyAr` is the same as none: a card must never be empty because somebody saved an empty field.
        assertEquals("Body c", broadcastBody(row("c", BroadcastKind.ANNOUNCEMENT, bodyAr = "   "), arabic = true))
    }

    // ---- 6. the plan's week reads as a date, and an unparseable one is shown as written

    @Test fun theWeekOfAPlanIsSpelledOut() {
        assertEquals("Week of 27 September", weekLabel("2026-09-27", Strings.en))
        assertEquals("أسبوع 27 سبتمبر", weekLabel("2026-09-27", Strings.ar))
        assertEquals("not-a-date", weekLabel("not-a-date", Strings.en))
    }

    // ---- 7. a11y: the row reads as words, including the unread badge and the attachment

    @Test fun theScreenReaderHearsTheBadgeAndWhereTheAttachmentIs() {
        val unread = row("a", BroadcastKind.WEEKLY_PLAN, curriculum = Curriculum.BRITISH)
            .copy(attachment = BroadcastAttachment("/media/pages/plan.pdf", "plan.pdf"))
        val said = broadcastDescription(unread, Strings.en)
        assertTrue(said.startsWith("New"), said)
        assertTrue(said.contains("From the British department manager"), said)
        assertTrue(said.contains("plan.pdf"), said)
        assertTrue(said.contains("Available on the dashboard"), said)

        // Once read, the badge is gone from what is spoken as well as from what is drawn.
        assertTrue(!broadcastDescription(unread.copy(read = true), Strings.en).startsWith("New"))
    }

    @Test fun theScreenReaderHearsAnExternalAttachmentWithoutDashboardFallback() {
        val external = row("a", BroadcastKind.WEEKLY_PLAN, curriculum = Curriculum.BRITISH)
            .copy(attachment = BroadcastAttachment("https://example.com/plan.pdf", "plan.pdf"))
        val said = broadcastDescription(external, Strings.en)
        assertTrue(said.contains("plan.pdf"), said)
        assertTrue(!said.contains("Available on the dashboard"), said)
    }
}
