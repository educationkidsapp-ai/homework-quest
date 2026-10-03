package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.presentation.ChatConversationRoute
import quest.feature.chat.presentation.ChatThreadsRoute
import quest.feature.chat.presentation.CoordinatorPickerRoute
import quest.feature.broadcasts.presentation.BroadcastsRoute
import quest.feature.broadcasts.presentation.WeeklyPlanRoute
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.ParentGate
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
        ParentHomeRoute(
            onCalendar = { nav.navigate(Routes.Calendar) }, onProgress = { nav.navigate(Routes.Progress) }, onSettings = { nav.navigate(Routes.Settings) },
            onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) },
            onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } }, onExit = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } },
            onMessages = { nav.navigate(Routes.ChatThreads) },
            onBroadcasts = { nav.navigate(Routes.Broadcasts()) },
            onWeeklyPlan = { nav.navigate(Routes.WeeklyPlan()) },
        )
    }
    composable<Routes.Calendar> { CalendarRoute(onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.Progress> { ProgressRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.Settings> {
        SettingsRoute(
            onChangePin = { nav.navigate(Routes.ChangePin) },
            onBack = { nav.popBackStack() },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onNotifications = { nav.navigate(Routes.Broadcasts()) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onMessages = { nav.navigate(Routes.ChatThreads) { popUpTo(Routes.ParentHome) { inclusive = false } } },
        )
    }
    composable<Routes.ChangePin> { PinRoute(onUnlocked = { nav.popBackStack() }, onBack = { nav.popBackStack() }, changePin = true) }
    composable<Routes.LessonPanel> { entry -> LessonPanelRoute(entry.toRoute<Routes.LessonPanel>().lessonId, onBack = { nav.popBackStack() }) }
    composable<Routes.ChatThreads> {
        ChatThreadsRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { nav.navigate(it.asConversation()) },
            onMessageCoordinator = { nav.navigate(Routes.ChatCoordinators) },
            onHome = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onNotifications = { nav.navigate(Routes.Broadcasts()) { popUpTo(Routes.ParentHome) { inclusive = false } } },
            onSettings = { nav.navigate(Routes.Settings) { popUpTo(Routes.ParentHome) { inclusive = false } } },
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
        )
    }
    // MH3: a detail page reached from Home, like Calendar and Progress — a back arrow and no bottom bar, because the
    // bar's four tabs are Home, Announcements, Messages and Settings and the plan is none of them.
    composable<Routes.WeeklyPlan> { entry -> WeeklyPlanRoute(focus = entry.toRoute<Routes.WeeklyPlan>().focus, onBack = { nav.popBackStack() }) }
    composable<Routes.ChatCoordinators> {
        CoordinatorPickerRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { thread, complaint -> nav.navigate(thread.asConversation().copy(complaint = complaint)) },
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
                topic = if (route.topic == "complaint") ChatTopic.COMPLAINT else ChatTopic.QUESTION,
                resolved = route.resolved,
                threadId = route.threadId,
                startAsComplaint = route.complaint,
                withAdmin = route.admin,
                peerOnline = route.peerOnline?.toBooleanStrictOrNull(),
                peerRole = ChatPeerRole.entries.firstOrNull { it.name == route.peerRole },
            ),
            onBack = { nav.popBackStack() },
        )
    }
}

/**
 * R8: the row the parent tapped already knows the role, the subject, the topic and the status, so the conversation
 * opens with its header, its badge and its banner right rather than asking the server again for what it was just told.
 */
internal fun ChatThread.asConversation() = Routes.ChatConversation(
    childId = childId,
    teacherId = teacherId,
    teacherName = teacherName,
    staffRole = staffRole.name,
    subject = subject,
    topic = if (topic == ChatTopic.COMPLAINT) "complaint" else "question",
    resolved = status == ChatThreadStatus.RESOLVED,
    threadId = id,
    admin = withAdmin == true,
    peerOnline = peerOnline?.toString(),
    peerRole = peerRole?.name,
)
