package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import quest.core.navigation.Routes
import quest.feature.lesson.presentation.AddLessonRoute
import quest.feature.lesson.presentation.ConfirmSkillsRoute
import quest.feature.lesson.presentation.ReadingRoute

/**
 * Parent-mode graph. The only way in is the PIN route; nothing here is reachable from child screens
 * except that entry point, and leaving parent mode pops the whole subgraph.
 */
fun NavGraphBuilder.parentGraph(nav: NavHostController) {
    fun exitToChild() { nav.popBackStack(Routes.ParentHome, inclusive = true) }
    fun home() { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentHome) { inclusive = true } } }

    composable<Routes.ParentPin> {
        PinRoute(onUnlocked = { nav.navigate(Routes.ParentHome) { popUpTo(Routes.ParentPin) { inclusive = true } } }, onBack = { nav.popBackStack() })
    }
    composable<Routes.ParentHome> {
        ParentHomeRoute(
            onAddLesson = { nav.navigate(Routes.AddLesson()) },
            onOpenLesson = { lesson ->
                when (lesson.status) {
                    quest.api.dto.LessonStatus.NEEDS_CONFIRMATION -> nav.navigate(Routes.ConfirmSkills(lesson.id))
                    quest.api.dto.LessonStatus.READY -> nav.navigate(Routes.Progress)
                    else -> nav.navigate(Routes.Reading(lesson.id))
                }
            },
            onCalendar = { nav.navigate(Routes.Calendar) },
            onProgress = { nav.navigate(Routes.Progress) },
            onSettings = { nav.navigate(Routes.Settings) },
            onProfile = { nav.navigate(Routes.Profile) },
            onExit = ::exitToChild,
        )
    }
    composable<Routes.Profile> { ProfileRoute(onDone = { nav.popBackStack() }, onBack = { nav.popBackStack() }) }
    composable<Routes.AddLesson> { entry ->
        val route = entry.toRoute<Routes.AddLesson>()
        AddLessonRoute(route.subject, typedMode = false, onOpenReading = { nav.navigate(Routes.Reading(it)) { popUpTo(Routes.ParentHome) } }, onTypeTask = { nav.navigate(Routes.TypedTask(it)) }, onBack = { nav.popBackStack() })
    }
    composable<Routes.TypedTask> { entry ->
        val route = entry.toRoute<Routes.TypedTask>()
        AddLessonRoute(route.subject, typedMode = true, onOpenReading = { nav.navigate(Routes.Reading(it)) { popUpTo(Routes.ParentHome) } }, onTypeTask = {}, onBack = { nav.popBackStack() })
    }
    composable<Routes.Reading> { entry ->
        val route = entry.toRoute<Routes.Reading>()
        ReadingRoute(
            route.lessonId,
            onConfirm = { nav.navigate(Routes.ConfirmSkills(it)) { popUpTo(Routes.ParentHome) } },
            onDone = ::home,
            onTryAgain = { nav.navigate(Routes.AddLesson()) { popUpTo(Routes.ParentHome) } },
            onBack = ::home,
        )
    }
    composable<Routes.ConfirmSkills> { entry ->
        val route = entry.toRoute<Routes.ConfirmSkills>()
        ConfirmSkillsRoute(route.lessonId, onConfirmed = { nav.navigate(Routes.Reading(it)) { popUpTo(Routes.ParentHome) } }, onBack = ::home)
    }
    composable<Routes.Calendar> { CalendarRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.Progress> { ProgressRoute(onBack = { nav.popBackStack() }) }
    composable<Routes.Settings> { SettingsRoute(onChangePin = { nav.navigate(Routes.ChangePin) }, onBack = { nav.popBackStack() }) }
    composable<Routes.ChangePin> { PinRoute(onUnlocked = { nav.popBackStack() }, onBack = { nav.popBackStack() }, changePin = true) }
}
