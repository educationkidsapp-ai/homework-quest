package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.feature.chat.presentation.ChatConversationRoute
import quest.feature.chat.presentation.ChatThreadsRoute
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
            onOpenConversation = { childId, teacherId, teacherName ->
                nav.navigate(Routes.ChatConversation(childId, teacherId, teacherName))
            },
        )
    }
    composable<Routes.ChatConversation> { entry ->
        val route = entry.toRoute<Routes.ChatConversation>()
        ChatConversationRoute(
            childId = route.childId,
            teacherId = route.teacherId,
            teacherName = route.teacherName,
            onBack = { nav.popBackStack() },
        )
    }
}
