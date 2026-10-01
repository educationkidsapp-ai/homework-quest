package quest

import quest.ui.design.LocalDarkTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import quest.feature.journey.presentation.LessonTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import org.koin.compose.KoinContext
import org.koin.compose.koinInject
import quest.api.AuthProvider
import quest.api.AuthState
import quest.core.navigation.Routes
import quest.di.AppInitializer
import quest.feature.auth.presentation.SignInRoute
import quest.feature.children.presentation.ChildPickerRoute
import quest.feature.journey.presentation.JourneyRoute
import quest.feature.journey.presentation.LessonCompleteRoute
import quest.feature.journey.presentation.StopPlayerRoute
import quest.feature.map.presentation.WorldMapRoute
import quest.feature.parent.presentation.parentGraph
import quest.feature.school.presentation.LevelGate
import quest.feature.school.presentation.SchoolThemeHost
import quest.ui.design.AcademicTheme
import quest.ui.design.AnimatedLoadingView

/** Root of the shared UI. Koin must already be started by the platform entry point. */
@Composable
fun App() {
    KoinContext {
        val initializer: AppInitializer = koinInject()
        val auth: AuthProvider = koinInject()
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { initializer.initialise(); ready = true }
        if (!ready) {
            // Before the settings are read there is no Light/Dark choice to honour, so the first frame follows the device.
            CompositionLocalProvider(LocalDarkTheme provides isSystemInDarkTheme()) { AcademicTheme { AnimatedLoadingView("…") } }
            return@KoinContext
        }
        val nav = rememberNavController()
        val start: Any = if (auth.state.value is AuthState.SignedIn) Routes.WorldMap else Routes.SignIn
        // Everything below sees the joined school's colours, name, logo and feature flags (§3, §4).
        SchoolThemeHost { QuestNavHost(nav, start) }
    }
}

@Composable
fun QuestNavHost(nav: NavHostController, start: Any) {
    NavHost(navController = nav, startDestination = start) {
        // After sign-in the parent sees every child the school linked to the account, and picks whose home to open.
        composable<Routes.SignIn> { SignInRoute(onSignedIn = { nav.navigate(Routes.ChildPicker) { popUpTo(Routes.SignIn) { inclusive = true } } }) }
        composable<Routes.ChildPicker> {
            ChildPickerRoute(
                onPicked = { nav.navigate(Routes.WorldMap) { popUpTo(0) { inclusive = true } } },
                onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } },
                onBack = if (nav.previousBackStackEntry != null) ({ nav.popBackStack() }) else null,
            )
        }
        composable<Routes.WorldMap> {
            AcademicTheme {
                WorldMapRoute(
                    onSwitchChild = { nav.navigate(Routes.ChildPicker) },
                    onOpenLesson = { id, level, variant -> nav.navigate(Routes.Journey(id, level, variant)) },
                    onGrownUps = { nav.navigate(Routes.ParentPin()) },
                    // Nobody linked yet: the children list is where the app says so.
                    onNeedsChild = { nav.navigate(Routes.ChildPicker) { popUpTo(0) { inclusive = true } } },
                )
            }
        }
        composable<Routes.Journey> { entry ->
            val r = entry.toRoute<Routes.Journey>()
            LessonTheme {
                // §4 `levels.three`: the route is the last door into the third level. A student who reaches it any
                // other way — a deep link, a back stack from before the flag was turned off — lands on the home page
                // instead of a level whose own selector would not even list it.
                LevelGate(r.level, onRefused = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } }) {
                    JourneyRoute(r.lessonId, r.level, r.variant,
                        onOpenStop = { id, level, variant, index -> nav.navigate(Routes.StopPlayer(id, level, variant, index)) },
                        onComplete = { id, level, variant -> nav.navigate(Routes.LessonComplete(id, level, variant)) },
                        onBack = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } })
                }
            }
        }
        composable<Routes.StopPlayer> { entry ->
            val r = entry.toRoute<Routes.StopPlayer>()
            LessonTheme {
                StopPlayerRoute(r.lessonId, r.level, r.variant, r.index,
                    onFinished = { id, level, variant -> nav.navigate(Routes.LessonComplete(id, level, variant)) { popUpTo(Routes.Journey(id, level, variant)) { inclusive = true } } },
                    onBack = { nav.popBackStack() })
            }
        }
        composable<Routes.LessonComplete> { entry ->
            val r = entry.toRoute<Routes.LessonComplete>()
            LessonTheme {
                LessonCompleteRoute(r.lessonId, r.level, r.variant,
                    onAgain = { id, level, variant -> nav.navigate(Routes.Journey(id, level, variant)) { popUpTo(Routes.WorldMap) } },
                    onNextLevel = { id, level -> nav.navigate(Routes.Journey(id, level, 0)) { popUpTo(Routes.WorldMap) } },
                    onHome = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } })
            }
        }
        parentGraph(nav)
    }
}
