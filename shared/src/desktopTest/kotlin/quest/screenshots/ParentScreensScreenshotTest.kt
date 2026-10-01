package quest.screenshots

import quest.feature.parent.domain.Appearance
import quest.ui.design.LocalDarkTheme
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
import quest.feature.children.presentation.ChildrenContract
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
import quest.ui.design.LocalThemeOverrides
import quest.ui.design.ParentTheme
import quest.ui.design.ThemeOverrides
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.api.dto.ReleasedResult
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.presentation.ChatConversationContract
import quest.feature.chat.presentation.ChatConversationScreen
import quest.feature.chat.presentation.ChatThreadsContract
import quest.feature.chat.presentation.ChatThreadsScreen
import quest.feature.chat.presentation.CoordinatorPickerContract
import quest.feature.chat.presentation.CoordinatorPickerScreen
import quest.api.dto.BroadcastAttachment
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.feature.broadcasts.domain.BroadcastGroups
import quest.feature.broadcasts.presentation.BroadcastsContract
import quest.feature.broadcasts.domain.PlanWeek
import quest.feature.broadcasts.domain.WeeklyPlans
import quest.feature.broadcasts.presentation.BroadcastsScreen
import quest.feature.broadcasts.presentation.WeeklyPlanContract
import quest.feature.broadcasts.presentation.WeeklyPlanScreen
import quest.ui.design.DashboardBottomNavigation
import quest.ui.design.DashboardTab
import kotlin.test.Test
import kotlin.test.assertTrue

class ParentScreensScreenshotTest {
    private val today = LocalDate(2026, 9, 14)
    private val maya = Child("c1", "Maya", "sun", Curriculum.BRITISH, 1)
    private val omar = Child("c2", "Omar", "mint", Curriculum.AMERICAN, 2)

    private fun shot(name: String, strings: Strings = Strings.en, overrides: ThemeOverrides = ThemeOverrides(), dark: Boolean = false, content: @Composable (Strings) -> Unit) {
        val f = Screenshots.render(name) {
            CompositionLocalProvider(LocalThemeOverrides provides overrides, LocalDarkTheme provides dark) {
                ParentTheme(rtl = strings.isRtl) { CompositionLocalProvider(LocalStrings provides strings) { content(strings) } }
            }
        }
        assertTrue(f.length() > 1000)
    }

    @Test fun signIn() = shot("40-sign-in") { s -> SignInScreen(SignInContract.State(email = "parent@example.com"), s, {}) }
    /** M1: after sign-in — every child the school linked to the parent. */
    @Test fun childPicker() = shot("42-child-picker") { s -> ChildPickerScreen(ChildrenContract.State(loading = false, children = listOf(maya, omar), currentId = "c1"), s) {} }
    /** M1: nobody linked yet. The admin adds children; the app can only say so. */
    @Test fun childPickerEmpty() = shot("42b-child-picker-empty") { s -> ChildPickerScreen(ChildrenContract.State(loading = false), s) {} }
    @Test fun childPickerArabic() = shot("42c-child-picker-ar", Strings.ar) { s -> ChildPickerScreen(ChildrenContract.State(loading = false, children = listOf(maya, omar), currentId = "c1"), s) {} }
    @Test fun pin() = shot("43-pin") { s -> PinScreen(PinContract.State(PinContract.Mode.ENTER, "12"), {}, s) }
    @Test fun home() = shot("44-parent-home") { s -> ParentHomeScreen(ParentHomeContract.State(false, listOf(maya, omar), maya, listOf(CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH), listOf("l1", "l2"), listOf("l2")))), s, {}, {}, {}, {}, {}) }
    @Test fun homeArabic() = shot("44b-parent-home-ar", Strings.ar) { s -> ParentHomeScreen(ParentHomeContract.State(false, listOf(maya), maya, listOf(CalendarDay(today, listOf(Subject.ENGLISH), listOf("l2"), listOf("l2")))), s, {}, {}, {}, {}, {}) }
    @Test fun calendar() = shot("45-calendar") { s -> CalendarScreen(CalendarContract.State(2026, 9, today, today, mapOf(today to CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH), listOf("l1", "l2"), listOf("l2")), LocalDate(2026, 9, 11) to CalendarDay(LocalDate(2026, 9, 11), listOf(Subject.ENGLISH), listOf("l0"), listOf("l0")))), s, {}, {}) }
    @Test fun progress() = shot("46-progress") { s -> ProgressScreen(ProgressContract.State(false, listOf(
        SkillReport("s1", "Counting by 2s", Subject.MATH, Band.GOING_WELL, "most of the time", 14, null),
        SkillReport("s2", "The sh sound", Subject.ENGLISH, Band.NEEDS_ANOTHER_LOOK, "some of the time", 7, null),
        SkillReport("s3", "Retelling a story", Subject.ENGLISH, null, null, 0, null))), s) }
    /** D16 slice 4: the released score, the band and the teacher's note — the one place in the app a score is shown. */
    @Test fun progressWithReleasedResults() = shot("46b-progress-released") { s ->
        ProgressScreen(
            ProgressContract.State(
                loading = false,
                reports = listOf(SkillReport("s1", "Counting by 2s", Subject.MATH, Band.GOING_WELL, "most of the time", 14, null)),
                streakDays = 3,
                results = listOf(
                    ReleasedResult("l1", "Counting in 2s", today, Subject.MATH, 82, "secure", "Lovely work on the number line — try the harder gaps next.", 1_757_800_000_000),
                    ReleasedResult("l2", "The sh sound", LocalDate(2026, 9, 11), Subject.ENGLISH, 64, "developing", null, 1_757_600_000_000),
                ),
            ),
            s,
        )
    }

    /** MH3: the Mobile number section appears once `GET /parent/me` has answered, which is what `phoneKnown` says. */
    @Test fun settings() = shot("47-settings") { s -> SettingsScreen(SettingsContract.State(false, ParentSettings("en"), phone = "+971501234567", phoneKnown = true), s, {}, {}) }
    @Test fun settingsArabic() = shot("47b-settings-ar", Strings.ar) { s -> SettingsScreen(SettingsContract.State(false, ParentSettings("ar"), phone = "+971501234567", phoneKnown = true), s, {}, {}) }
    @Test fun lessonPanel() = shot("48-lesson-panel") { s -> LessonPanelScreen(HotSoupSeed.lesson, s) }
    @Test fun lessonPanelArabic() = shot("48b-lesson-panel-ar", Strings.ar) { s -> LessonPanelScreen(HotSoupSeed.lesson, s) }

    @Test fun chatThreads() = shot("49-chat-threads") { s ->
        ChatThreadsScreen(
            state = ChatThreadsContract.State(
                loading = false,
                threads = listOf(
                    ChatThread("th-1", "c1", "Maya", "t1", "Ms. Sara", "1A British", "Math", 2, ChatMessage("m1", "th-1", ChatSender.TEACHER, "t1", "Please make sure to review counting by 2s today.", 1_758_450_000_000L)),
                    ChatThread("th-2", "c1", "Maya", "t2", "Ms. Noor", "1A British", "English", 0, ChatMessage("m2", "th-2", ChatSender.PARENT, "p1", "Thank you, Maya enjoyed the story!", 1_758_440_000_000L)),
                    ChatThread(
                        "th-3", "c1", "Maya", "co1", "Ms. Lina", "1A British", "Math", 0,
                        ChatMessage("m3", "th-3", ChatSender.TEACHER, "co1", "I have spoken to the teacher about the homework.", 1_758_430_000_000L),
                        ChatStaffRole.COORDINATOR, ChatTopic.COMPLAINT, ChatThreadStatus.RESOLVED, 1_758_431_000_000L,
                    ),
                ),
            ),
            strings = s,
            onSelectThread = {},
        )
    }

    @Test fun chatThreadsArabic() = shot("49b-chat-threads-ar", Strings.ar) { s ->
        ChatThreadsScreen(
            state = ChatThreadsContract.State(
                loading = false,
                threads = listOf(
                    ChatThread("th-1", "c1", "مايا", "t1", "أ. سارة", "1A البريطاني", "رياضيات", 1, ChatMessage("m1", "th-1", ChatSender.TEACHER, "t1", "مرحبًا! يرجى مراجعة درس العد بالاثنينات.", 1_758_450_000_000L)),
                    ChatThread(
                        "th-3", "c1", "مايا", "co1", "أ. لينا", "1A البريطاني", "رياضيات", 0, null,
                        ChatStaffRole.COORDINATOR, ChatTopic.COMPLAINT, ChatThreadStatus.OPEN,
                    ),
                ),
            ),
            strings = s,
            onSelectThread = {},
        )
    }

    @Test fun chatConversation() = shot("50-chat-conversation") { s ->
        ChatConversationScreen(
            state = ChatConversationContract.State(
                childId = "c1",
                teacherId = "t1",
                teacherName = "Ms. Sara",
                loading = false,
                connectionState = ChatConnectionState.CONNECTED,
                messages = listOf(
                    ChatConversationContract.UiMessage("m1", "Hello! Welcome to the new term.", false, 1_758_450_000_000L),
                    ChatConversationContract.UiMessage("m2", "Hello Ms. Sara! Maya is very excited.", true, 1_758_450_100_000L, readAt = 1_758_450_200_000L),
                    ChatConversationContract.UiMessage("m3", "She did great in math today!", false, 1_758_450_300_000L),
                ),
            ),
            strings = s,
            onBack = {},
            onInputChange = {},
            onSend = {},
            onRetry = {},
        )
    }

    @Test fun chatConversationArabic() = shot("50b-chat-conversation-ar", Strings.ar) { s ->
        ChatConversationScreen(
            state = ChatConversationContract.State(
                childId = "c1",
                teacherId = "t1",
                teacherName = "أ. سارة",
                loading = false,
                connectionState = ChatConnectionState.CONNECTED,
                messages = listOf(
                    ChatConversationContract.UiMessage("m1", "أهلاً بك! مايا أدت أداءً رائعاً اليوم في الرياضيات.", false, 1_758_450_000_000L),
                    ChatConversationContract.UiMessage("m2", "شكراً جزيلاً أستاذة سارة! سعداء جداً بسماع ذلك.", true, 1_758_450_100_000L, readAt = 1_758_450_200_000L),
                ),
            ),
            strings = s,
            onBack = {},
            onInputChange = {},
            onSend = {},
            onRetry = {},
        )
    }

    // R8: the coordinator picker, and a complaint conversation the coordinator has resolved.
    @Test fun coordinatorPicker() = shot("51-coordinator-picker") { s ->
        CoordinatorPickerScreen(
            state = CoordinatorPickerContract.State(
                loading = false,
                coordinators = listOf(
                    ChatThread(null, "c1", "Maya", "co1", "Ms. Lina", "1A British", "Math", 0, null, ChatStaffRole.COORDINATOR),
                    ChatThread(null, "c1", "Maya", "co2", "Mr. Omar", "1A British", "English, Science", 0, null, ChatStaffRole.COORDINATOR),
                ),
            ),
            strings = s,
            onSelect = {},
        )
    }

    @Test fun coordinatorPickerArabic() = shot("51b-coordinator-picker-ar", Strings.ar) { s ->
        CoordinatorPickerScreen(
            state = CoordinatorPickerContract.State(
                loading = false,
                coordinators = listOf(
                    ChatThread(null, "c1", "مايا", "co1", "أ. لينا", "1A البريطاني", "رياضيات", 0, null, ChatStaffRole.COORDINATOR),
                ),
            ),
            strings = s,
            onSelect = {},
        )
    }

    @Test fun chatComplaintResolved() = shot("52-chat-complaint-resolved") { s ->
        ChatConversationScreen(
            state = ChatConversationContract.State(
                childId = "c1", teacherId = "co1", teacherName = "Ms. Lina",
                loading = false,
                connectionState = ChatConnectionState.CONNECTED,
                staffRole = ChatStaffRole.COORDINATOR,
                subject = "Math",
                topic = ChatTopic.COMPLAINT,
                resolved = true,
                messages = listOf(
                    ChatConversationContract.UiMessage("m1", "The nightly homework is taking Maya over an hour.", true, 1_758_450_000_000L, readAt = 1_758_450_050_000L),
                    ChatConversationContract.UiMessage("m2", "Thank you — I have asked the teacher to shorten it this week.", false, 1_758_450_300_000L),
                ),
            ),
            strings = s,
            onBack = {}, onInputChange = {}, onSend = {}, onRetry = {}, onToggleComplaint = {},
        )
    }

    @Test fun chatComplaintToggle() = shot("53-chat-complaint-toggle") { s ->
        ChatConversationScreen(
            state = ChatConversationContract.State(
                childId = "c1", teacherId = "co1", teacherName = "Ms. Lina",
                loading = false,
                connectionState = ChatConnectionState.CONNECTED,
                staffRole = ChatStaffRole.COORDINATOR,
                subject = "Math",
                markAsComplaint = true,
                inputText = "The nightly homework is taking Maya over an hour.",
            ),
            strings = s,
            onBack = {}, onInputChange = {}, onSend = {}, onRetry = {}, onToggleComplaint = {},
        )
    }

    // RM4, split by MH3: the Announcements feed, the Weekly plan page, and the picker with the department manager.
    private fun plan(week: String, read: Boolean = false) = BroadcastView(
        id = "bc-plan-$week", kind = BroadcastKind.WEEKLY_PLAN, authorId = "mg", authorName = "Ms. Nour",
        authorRole = ChatStaffRole.MANAGERIAL, weekStart = week, grade = 1,
        bodyEn = "Weekly plan · Grade 1 · week of $week",
        curriculum = Curriculum.BRITISH,
        attachment = BroadcastAttachment("/media/attachments/att-1", "week-plan.png", "att-1", "image/png"),
        createdAt = 1_758_500_000_000L, read = read,
    )

    private fun announcement() = BroadcastView(
        id = "bc-ann", kind = BroadcastKind.ANNOUNCEMENT, authorId = "co", authorName = "Ms. Lina",
        authorRole = ChatStaffRole.COORDINATOR, title = "New number lines",
        bodyEn = "We have put number lines on every desk — practise counting back from 20 at home.",
        bodyAr = "وضعنا خطوط الأعداد على كل مقعد — تدرّبوا على العدّ التنازلي من 20 في البيت.",
        subject = "math", createdAt = 1_758_400_000_000L, read = true,
    )

    private fun event() = BroadcastView(
        id = "bc-event", kind = BroadcastKind.EVENT, authorId = "mg", authorName = "Ms. Nour",
        authorRole = ChatStaffRole.MANAGERIAL, title = "Sports day",
        bodyEn = "Sports day is on the last Thursday of the month. Parents are welcome.",
        bodyAr = "يوم الرياضة في آخر خميس من الشهر. الأهل مرحّب بهم.",
        curriculum = Curriculum.BRITISH, createdAt = 1_758_300_000_000L,
    )

    private fun feedState() = BroadcastsContract.State(
        loading = false, unread = 1,
        groups = BroadcastGroups(announcements = listOf(announcement()), events = listOf(event())),
    )

    @Test fun broadcasts() = shot("54-announcements") { s -> BroadcastsScreen(state = feedState(), strings = s) }

    @Test fun broadcastsArabic() = shot("54b-announcements-ar", Strings.ar) { s ->
        BroadcastsScreen(state = feedState(), strings = s)
    }

    /** A school without the `announcements` flag: the server 404s and the screen says so rather than showing an error. */
    @Test fun broadcastsNotEnabled() = shot("54c-announcements-off") { s ->
        BroadcastsScreen(state = BroadcastsContract.State(loading = false, notEnabled = true), strings = s)
    }

    /**
     * MH3: the Weekly plan page. `LocalAttachmentImages` is the no-op here, so the pinned plan draws its **Try again**
     * state — which is exactly what the page looks like offline with nothing in the cache, and worth a shot of its own.
     */
    private fun planState() = WeeklyPlanContract.State(
        loading = false, unread = 1, grade = 1,
        plans = WeeklyPlans(
            current = plan("2026-09-27"),
            earlier = listOf(
                PlanWeek("2026-09-20", listOf(plan("2026-09-20", read = true))),
                PlanWeek("2026-09-13", listOf(plan("2026-09-13", read = true))),
            ),
        ),
    )

    @Test fun weeklyPlan() = shot("56-weekly-plan") { s -> WeeklyPlanScreen(state = planState(), strings = s) }

    @Test fun weeklyPlanArabic() = shot("56b-weekly-plan-ar", Strings.ar) { s ->
        WeeklyPlanScreen(state = planState(), strings = s)
    }

    /** No plan yet: the page says the manager sends one each week rather than showing an empty frame. */
    @Test fun weeklyPlanEmpty() = shot("56c-weekly-plan-empty") { s ->
        WeeklyPlanScreen(state = WeeklyPlanContract.State(loading = false), strings = s)
    }

    @Test fun peerPickerWithManager() = shot("55-peer-picker") { s ->
        CoordinatorPickerScreen(
            state = CoordinatorPickerContract.State(
                loading = false,
                coordinators = listOf(
                    ChatThread(null, "c1", "Maya", "co1", "Ms. Lina", "1A British", "Math", 0, null, ChatStaffRole.COORDINATOR),
                ),
                managers = listOf(
                    ChatThread(null, "c1", "Maya", "mg1", "Ms. Nour", "1A British", null, 0, null, ChatStaffRole.MANAGERIAL),
                ),
                curriculum = Curriculum.BRITISH,
            ),
            strings = s,
            onSelect = {},
        )
    }

    @Test fun peerPickerWithManagerArabic() = shot("55b-peer-picker-ar", Strings.ar) { s ->
        CoordinatorPickerScreen(
            state = CoordinatorPickerContract.State(
                loading = false,
                coordinators = listOf(
                    ChatThread(null, "c1", "مايا", "co1", "أ. لينا", "1A البريطاني", "رياضيات", 0, null, ChatStaffRole.COORDINATOR),
                ),
                managers = listOf(
                    ChatThread(null, "c1", "مايا", "mg1", "أ. نور", "1A البريطاني", null, 0, null, ChatStaffRole.MANAGERIAL),
                ),
                curriculum = Curriculum.BRITISH,
            ),
            strings = s,
            onSelect = {},
        )
    }

    /**
     * MH4: the bottom bar with `announcements` off. §4's rule is that a school without a feature never learns it
     * exists, so the Notifications tab is absent rather than present-and-bouncing.
     */
    @Test fun bottomNavWithoutAnnouncements() = shot("56-bottom-nav-no-announcements") {
        DashboardBottomNavigation(DashboardTab.HOME, {}, showNotifications = false)
    }

    @Test fun bottomNavArabic() = shot("56b-bottom-nav-ar", Strings.ar) { s ->
        DashboardBottomNavigation(DashboardTab.MESSAGES, {}, isRtl = s.isRtl, unreadMessages = 2)
    }

    /** M1: New message — the child, question or complaint, then a teacher, a coordinator or the manager. */
    private fun newMessageState(complaint: Boolean) = CoordinatorPickerContract.State(
        loading = false, children = listOf(maya, omar), childId = "c1", complaint = complaint,
        teachers = listOf(
            ChatThread(null, "c1", "Maya", "t1", "Ms. Sara", "1A British", "Math", 0, null, ChatStaffRole.TEACHER),
            ChatThread("th9", "c1", "Maya", "t2", "Mr. Adam", "1A British", "English", 0, null, ChatStaffRole.TEACHER, quest.api.dto.ChatTopic.COMPLAINT),
        ),
        coordinators = listOf(ChatThread(null, "c1", "Maya", "co1", "Ms. Lina", "1A British", "Math", 0, null, ChatStaffRole.COORDINATOR)),
        managers = listOf(ChatThread(null, "c1", "Maya", "mg1", "Ms. Nour", "1A British", null, 0, null, ChatStaffRole.MANAGERIAL)),
        curriculum = Curriculum.BRITISH,
    )
    @Test fun newMessage() = shot("57-new-message") { s -> CoordinatorPickerScreen(newMessageState(false), s, {}) }
    @Test fun newMessageComplaint() = shot("57b-new-message-complaint") { s -> CoordinatorPickerScreen(newMessageState(true), s, {}) }
    @Test fun newMessageComplaintArabic() = shot("57c-new-message-complaint-ar", Strings.ar) { s -> CoordinatorPickerScreen(newMessageState(true), s, {}) }

    /** M1: a weekly plan uploaded as a PDF — the file's name and an Open button instead of an image. */
    @Test fun weeklyPlanPdf() = shot("56d-weekly-plan-pdf") { s ->
        val pdf = plan("2026-09-27").copy(attachment = BroadcastAttachment("/media/attachments/att-pdf", "Grade 1 weekly plan.pdf", "att-pdf", "application/pdf"))
        WeeklyPlanScreen(state = planState().let { it.copy(plans = it.plans.copy(current = pdf)) }, strings = s)
    }

    // ---- dark palette ----------------------------------------------------------------------------------------------
    @Test fun signInDark() = shot("40b-sign-in-dark", dark = true) { s -> SignInScreen(SignInContract.State(email = "parent@example.com"), s, {}) }
    @Test fun childPickerDark() = shot("42d-child-picker-dark", dark = true) { s -> ChildPickerScreen(ChildrenContract.State(loading = false, children = listOf(maya, omar), currentId = "c1"), s) {} }
    @Test fun homeDark() = shot("44c-parent-home-dark", dark = true) { s -> ParentHomeScreen(ParentHomeContract.State(false, listOf(maya, omar), maya, listOf(CalendarDay(today, listOf(Subject.MATH, Subject.ENGLISH), listOf("l1", "l2"), listOf("l2")))), s, {}, {}, {}, {}, {}) }
    @Test fun settingsDark() = shot("47c-settings-dark", dark = true) { s -> SettingsScreen(SettingsContract.State(false, ParentSettings("en"), Appearance.DARK, phone = "+971501234567", phoneKnown = true), s, {}, {}) }
    @Test fun newMessageComplaintDark() = shot("57d-new-message-complaint-dark", dark = true) { s -> CoordinatorPickerScreen(newMessageState(true), s, {}) }
    @Test fun weeklyPlanPdfDark() = shot("56e-weekly-plan-pdf-dark", dark = true) { s ->
        val pdf = plan("2026-09-27").copy(attachment = BroadcastAttachment("/media/attachments/att-pdf", "Grade 1 weekly plan.pdf", "att-pdf", "application/pdf"))
        WeeklyPlanScreen(state = planState().let { it.copy(plans = it.plans.copy(current = pdf)) }, strings = s)
    }
    /** The bottom bar: the active tab is the dashboard's gradient nav item. */
    @Test fun bottomNavDark() = shot("56f-bottom-nav-dark", dark = true) { s ->
        androidx.compose.foundation.layout.Column { androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f)); DashboardBottomNavigation(DashboardTab.MESSAGES, {}, unreadMessages = 2) }
    }
}
