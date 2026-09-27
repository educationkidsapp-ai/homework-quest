package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.ChatThreadStatus
import quest.api.dto.ChatTopic
import quest.feature.chat.domain.ChatPeer
import quest.feature.chat.presentation.ChatConversationRoute
import quest.feature.chat.presentation.ChatThreadsRoute
import quest.feature.chat.presentation.CoordinatorPickerRoute
import quest.feature.broadcasts.presentation.BroadcastsRoute
import quest.feature.children.presentation.AddChildRoute

/** Parent-mode graph (behind the PIN). Nothing here is reachable from child screens except the PIN entry. */
fun NavGraphBuilder.parentGraph(nav: NavHostController) {
    composable<Routes.ParentPin> { entry ->
        val lessonId = entry.toRoute<Routes.ParentPin>().lessonId
        PinRoute(onUnlocked = {
            val target: Any = if (lessonId != null) Routes.LessonPanel(lessonId) else Routes.ParentHome
            nav.navigate(target) { popUpTo<Routes.ParentPin> { inclusive = true } }
        }, onBack = { nav.popBackStack() })
    }
    composable<Routes.ParentHome> {
        ParentHomeRoute(
            onAddChild = { nav.navigate(Routes.AddChild()) }, onEditChild = { nav.navigate(Routes.AddChild(it)) },
            onCalendar = { nav.navigate(Routes.Calendar) }, onProgress = { nav.navigate(Routes.Progress) }, onSettings = { nav.navigate(Routes.Settings) },
            onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) },
            onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } }, onExit = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } },
            onMessages = { nav.navigate(Routes.ChatThreads) },
            onBroadcasts = { nav.navigate(Routes.Broadcasts) },
        )
    }
    composable<Routes.Calendar> { CalendarRoute(onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.Progress> { ProgressRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.Settings> { SettingsRoute(onChangePin = { nav.navigate(Routes.ChangePin) }, onEditChild = { nav.navigate(Routes.AddChild(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.ChangePin> { PinRoute(onUnlocked = { nav.popBackStack() }, onBack = { nav.popBackStack() }, changePin = true) }
    composable<Routes.LessonPanel> { entry -> LessonPanelRoute(entry.toRoute<Routes.LessonPanel>().lessonId, onBack = { nav.popBackStack() }) }
    composable<Routes.ChatThreads> {
        ChatThreadsRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { nav.navigate(it.asConversation()) },
            onMessageCoordinator = { nav.navigate(Routes.ChatCoordinators) },
        )
    }
    composable<Routes.Broadcasts> { BroadcastsRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.ChatCoordinators> {
        CoordinatorPickerRoute(
            onBack = { nav.popBackStack() },
            onOpenConversation = { nav.navigate(it.asConversation()) },
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
            ),
            onBack = { nav.popBackStack() },
        )
    }
}

/**
 * R8: the row the parent tapped already knows the role, the subject, the topic and the status, so the conversation
 * opens with its header, its badge and its banner right rather than asking the server again for what it was just told.
 */
private fun ChatThread.asConversation() = Routes.ChatConversation(
    childId = childId,
    teacherId = teacherId,
    teacherName = teacherName,
    staffRole = staffRole.name,
    subject = subject,
    topic = if (topic == ChatTopic.COMPLAINT) "complaint" else "question",
    resolved = status == ChatThreadStatus.RESOLVED,
    threadId = id,
)
