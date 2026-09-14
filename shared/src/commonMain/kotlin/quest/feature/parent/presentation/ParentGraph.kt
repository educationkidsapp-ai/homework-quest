package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.feature.children.presentation.AddChildRoute

/** Parent-mode graph (behind the PIN). Nothing here is reachable from child screens except the PIN entry. */
fun NavGraphBuilder.parentGraph(nav: NavHostController) {
    fun exitToChild() { nav.popBackStack(Routes.ParentHome, inclusive = true) }
    composable<Routes.ParentPin> { PinRoute(onUnlocked = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentPin) { inclusive = true } } }, onBack = { nav.popBackStack() }) }
    composable<Routes.ParentHome> {
        ParentHomeRoute(
            onAddChild = { nav.navigate(Routes.AddChild()) }, onEditChild = { nav.navigate(Routes.AddChild(it)) },
            onCalendar = { nav.navigate(Routes.Calendar) }, onProgress = { nav.navigate(Routes.Progress) }, onSettings = { nav.navigate(Routes.Settings) },
            onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) },
            onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } }, onExit = ::exitToChild,
        )
    }
    composable<Routes.Calendar> { CalendarRoute(onLessonPanel = { nav.navigate(Routes.LessonPanel(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.Progress> { ProgressRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.Settings> { SettingsRoute(onChangePin = { nav.navigate(Routes.ChangePin) }, onEditChild = { nav.navigate(Routes.AddChild(it)) }, onBack = { nav.popBackStack() }) }
    composable<Routes.ChangePin> { PinRoute(onUnlocked = { nav.popBackStack() }, onBack = { nav.popBackStack() }, changePin = true) }
    composable<Routes.LessonPanel> { entry -> LessonPanelRoute(entry.toRoute<Routes.LessonPanel>().lessonId, onBack = { nav.popBackStack() }) }
}
