package quest.feature.parent.presentation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import quest.api.dto.ChannelPost
import quest.api.dto.TeamMember
import quest.feature.channel.presentation.ClassChannelRoute
import quest.feature.children.domain.ChildrenRepository
import quest.feature.team.presentation.ChildTeamRoute
import quest.api.dto.ParentEvaluation
import quest.feature.evaluations.presentation.ChildEvaluationsRoute
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.domain.staffRoleOf
import quest.feature.chat.presentation.ChatConversationRoute
import quest.feature.chat.presentation.ChatThreadsRoute
import quest.feature.chat.presentation.CoordinatorPickerRoute
import quest.feature.complaints.presentation.ComplaintRoute
import quest.feature.complaints.presentation.ComplaintsRoute
import quest.feature.complaints.presentation.NewComplaintRoute
import quest.feature.broadcasts.presentation.BroadcastsRoute
import quest.feature.broadcasts.presentation.WeeklyPlanRoute
import quest.api.dto.OfficeHoursStatus
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.ParentGate
import quest.feature.lock.domain.AppLock
import org.koin.compose.koinInject

/** Parent-mode graph (behind the PIN). Nothing here is reachable from child screens except the PIN entry. */
fun NavGraphBuilder.parentGraph(nav: NavHostController) {
    composable<Routes.ParentPin> { entry ->
        val route = entry.toRoute<Routes.ParentPin>()
        val gate: ParentGate = koinInject()
        PinRoute(onUnlocked = {
            gate.passed()
            val target: Any = if (route.lessonId != null) Routes.LessonPanel(route.lessonId) else Routes.ParentHome
            nav.navigate(target) { popUpTo<Routes.ParentPin> { inclusive = true } }
            // M5: a tapped notification goes on from the parent home, so Back from its page lands there.
            if (route.push) PushLinks.gateOpened()
        }, onBack = {
            // M5: backing out of the gate drops a waiting tap — its page is never shown.
            if (route.push) PushLinks.abandoned()
            nav.popBackStack()
        })
    }
    composable<Routes.ParentHome> {
        // M6: an account signed in before this version (or on a phone that had no screen lock then) is asked here.
        val lock: AppLock = koinInject()
        LaunchedEffect(lock) { lock.parentHomeOpened() }
        ParentHomeRoute(
            onCalendar = { nav.navigate(Routes.Calendar) }, onProgress = { nav.navigate(Routes.Progress()) }, onSettings = { nav.navigate(Routes.Settings) },
            onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) },
            onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } }, onExit = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } },
            onMessages = { nav.navigate(Routes.ChatThreads) },
            onBroadcasts = { nav.navigate(Routes.Broadcasts()) },
            onWeeklyPlan = { nav.navigate(Routes.WeeklyPlan()) },
            onComplaints = { nav.navigate(Routes.Complaints) },
            // Feature 13: her staff view, from its own first screen.
            onStaffSide = { nav.navigate(Routes.StaffHome) { popUpTo(0) { inclusive = true } } },
        )
    }
    composable<Routes.Calendar> { CalendarRoute(onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.Progress> { entry ->
        ProgressRoute(
            focusExam = entry.toRoute<Routes.Progress>().focusExam,
            onBack = { nav.popBackStack() },
            // Feature 33: "Teacher evaluations", one tap from Progress — which is where a parent already looks for
            // what the school thinks of her child.
            onEvaluations = { nav.navigate(Routes.ChildEvaluations) },
        )
    }
    composable<Routes.Settings> {
        SettingsRoute(
            onChangePin = { nav.navigate(Routes.ChangePin) },
            onBack = { nav.popBackStack() },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onNotifications = { nav.navigate(Routes.Broadcasts()) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onMessages = { nav.navigate(Routes.ChatThreads) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onComplaints = { nav.navigate(Routes.Complaints) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
    composable<Routes.ChangePin> { PinRoute(onUnlocked = { nav.popBackStack() }, onBack = { nav.popBackStack() }, changePin = true) }
    composable<Routes.LessonPanel> { entry -> LessonPanelRoute(entry.toRoute<Routes.LessonPanel>().lessonId, onBack = { nav.popBackStack() }) }
    composable<Routes.ChatThreads> {
        ChatThreadsRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { nav.navigate(it.asConversation()) },
            onMessageCoordinator = { nav.navigate(Routes.ChatCoordinators) },
            // Feature 32: the class channel and the child's team, each one tap from the Messages tab.
            onOpenChannel = { nav.navigate(Routes.ClassChannel) },
            onOpenTeam = { nav.navigate(Routes.ChildTeam) },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onNotifications = { nav.navigate(Routes.Broadcasts()) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onSettings = { nav.navigate(Routes.Settings) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onComplaints = { nav.navigate(Routes.Complaints) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
    composable<Routes.Broadcasts> { entry ->
        val route = entry.toRoute<Routes.Broadcasts>()
        BroadcastsRoute(
            focus = route,
            onBack = { nav.popBackStack() },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onMessages = { nav.navigate(Routes.ChatThreads) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onSettings = { nav.navigate(Routes.Settings) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onComplaints = { nav.navigate(Routes.Complaints) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
    // M8: the Complaints tab, New complaint and one complaint — the same bottom bar as Messages.
    composable<Routes.Complaints> {
        ComplaintsRoute(
            onBack = { nav.popBackStack() },
            onOpen = { nav.navigate(Routes.Complaint(it.childId, it.id)) },
            onNew = { nav.navigate(Routes.NewComplaint) },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onNotifications = { nav.navigate(Routes.Broadcasts()) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onMessages = { nav.navigate(Routes.ChatThreads) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onSettings = { nav.navigate(Routes.Settings) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
    composable<Routes.NewComplaint> {
        NewComplaintRoute(
            onBack = { nav.popBackStack() },
            // Sent: the complaint itself, with Back landing on the list rather than on an emptied form.
            onCreated = { nav.navigate(Routes.Complaint(it.childId, it.id)) { popUpTo<Routes.NewComplaint> { inclusive = true } } },
        )
    }
    composable<Routes.Complaint> { entry ->
        val route = entry.toRoute<Routes.Complaint>()
        ComplaintRoute(route.childId, route.complaintId, onBack = { nav.popBackStack() })
    }
    // MH3: a detail page reached from Home, like Calendar and Progress — a back arrow and no bottom bar, because the
    // bar's four tabs are Home, Announcements, Messages and Settings and the plan is none of them.
    composable<Routes.WeeklyPlan> { entry -> WeeklyPlanRoute(focus = entry.toRoute<Routes.WeeklyPlan>().focus, onBack = { nav.popBackStack() }) }
    composable<Routes.ChatCoordinators> {
        CoordinatorPickerRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { nav.navigate(it.asConversation()) },
        )
    }
    // Feature 32: the child's team, and her class's channel. Both go on to the same conversation the thread list and
    // the coordinator picker open — a parent has one thread per staff member, however she reached it.
    composable<Routes.ChildTeam> {
        val childId = koinInject<ChildrenRepository>().currentChild.collectAsStateWithLifecycle().value?.id
        ChildTeamRoute(
            onBack = { nav.popBackStack() },
            onMessage = { member -> childId?.let { nav.navigate(member.asConversation(it)) } },
            // Feature 33: "Latest evaluation" on a teacher's row opens the page where the words themselves are.
            onLatestEvaluation = { nav.navigate(Routes.ChildEvaluations) },
        )
    }
    composable<Routes.ClassChannel> {
        val childId = koinInject<ChildrenRepository>().currentChild.collectAsStateWithLifecycle().value?.id
        ClassChannelRoute(
            onBack = { nav.popBackStack() },
            onReplyPrivately = { post -> childId?.let { nav.navigate(post.asConversation(it)) } },
        )
    }
    // Feature 33: the teachers' evaluations of the current child. Reply privately is the same conversation the team
    // page and the channel open — a parent has one thread per staff member, however she reached it.
    composable<Routes.ChildEvaluations> {
        val childId = koinInject<ChildrenRepository>().currentChild.collectAsStateWithLifecycle().value?.id
        ChildEvaluationsRoute(
            onBack = { nav.popBackStack() },
            onReplyPrivately = { row -> childId?.let { nav.navigate(row.asConversation(it)) } },
        )
    }
    composable<Routes.ChatConversation> { entry ->
        val route = entry.toRoute<Routes.ChatConversation>()
        ChatConversationRoute(
            peer = ChatPeer(
                childId = route.childId,
                staffId = route.teacherId,
                staffName = route.teacherName,
                staffRole = ChatStaffRole.entries.firstOrNull { it.name == route.staffRole } ?: ChatStaffRole.TEACHER,
                subject = route.subject,
                threadId = route.threadId,
                withAdmin = route.admin,
                peerOnline = route.peerOnline?.toBooleanStrictOrNull(),
                peerRole = ChatPeerRole.entries.firstOrNull { it.name == route.peerRole },
                // Feature 19: a status travels only when it is closed and names a next start, which is the one case
                // the banner says anything at all.
                officeHours = if (route.officeNextStart != null && route.officeToday != null) {
                    OfficeHoursStatus(open = false, nextStart = route.officeNextStart, today = route.officeToday, schoolPhone = route.officePhone)
                } else {
                    null
                },
            ),
            onBack = { nav.popBackStack() },
            onOpenComplaints = { nav.navigate(Routes.Complaints) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
}

/**
 * R8: the row the parent tapped already knows the role and the subject, so the conversation opens with its header
 * right rather than asking the server again for what it was just told.
 */
/**
 * Feature 32: a team row's **Message**. The row carries everything the conversation's header needs — who she is, what
 * she teaches, the thread if one exists — so, exactly as a thread row does, it opens without asking the server again.
 * A null `threadId` is a thread that does not exist yet: the first message she sends creates it.
 */
internal fun TeamMember.asConversation(childId: String) = Routes.ChatConversation(
    childId = childId,
    teacherId = staffId,
    teacherName = name,
    staffRole = staffRoleOf(role).name,
    subject = subjects,
    threadId = threadId,
    peerRole = role.name,
    officeNextStart = officeHours?.takeIf { !it.open }?.nextStart,
    officeToday = officeHours?.takeIf { !it.open && it.nextStart != null }?.today,
    officePhone = officeHours?.takeIf { !it.open }?.schoolPhone,
)

/**
 * Feature 32: **Reply privately** on a channel post — the parent's own one-to-one thread with the teacher who wrote
 * it, which is the only way she answers. The post names no thread (it is not one of hers), so the conversation finds
 * or starts hers from the staff id, as the coordinator picker's rows do.
 */
internal fun ChannelPost.asConversation(childId: String) = Routes.ChatConversation(
    childId = childId,
    teacherId = authorId,
    teacherName = authorName,
    staffRole = staffRoleOf(authorRole).name,
    subject = subject,
    peerRole = authorRole.name,
)

/**
 * Feature 33: **Reply privately** on an evaluation — the parent's own one-to-one thread with the teacher who wrote
 * it, which is the only way she answers one. The row names no thread (an evaluation is not a message), so the
 * conversation finds or starts hers from the teacher's id, exactly as a channel post's reply does.
 */
internal fun ParentEvaluation.asConversation(childId: String) = Routes.ChatConversation(
    childId = childId,
    teacherId = teacherId,
    teacherName = teacherName,
    staffRole = ChatStaffRole.TEACHER.name,
    subject = subject,
    peerRole = ChatPeerRole.TEACHER.name,
)

internal fun ChatThread.asConversation() = Routes.ChatConversation(
    childId = childId,
    teacherId = teacherId,
    teacherName = teacherName,
    staffRole = staffRole.name,
    subject = subject,
    threadId = id,
    admin = withAdmin == true,
    peerOnline = peerOnline?.toString(),
    peerRole = peerRole?.name,
    officeNextStart = officeHours?.takeIf { !it.open }?.nextStart,
    officeToday = officeHours?.takeIf { !it.open && it.nextStart != null }?.today,
    officePhone = officeHours?.takeIf { !it.open }?.schoolPhone,
)
