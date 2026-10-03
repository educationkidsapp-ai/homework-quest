package quest

import quest.feature.today.domain.TodayLinks
import quest.feature.today.domain.TodayLink
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.PushRegistration
import quest.feature.push.presentation.PushNavigator
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import androidx.compose.runtime.rememberCoroutineScope
import quest.feature.lock.presentation.AppLockHost
import quest.feature.lock.domain.AppLock
import quest.ui.design.LocalDarkTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import quest.feature.journey.presentation.LessonTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import quest.feature.parent.domain.ParentRepository
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
import quest.feature.content.domain.PendingAnswersSync
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import quest.feature.auth.presentation.SignInRoute
import quest.feature.children.presentation.ChildPickerRoute
import quest.feature.journey.presentation.JourneyRoute
import quest.feature.journey.presentation.ExamResultRoute
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
        val lock: AppLock = koinInject()
        // The lock is decided before the first screen is drawn, so a locked app never shows a frame of its content.
        LaunchedEffect(Unit) { initializer.initialise(); lock.coldStart(); ready = true }
        if (!ready) {
            // The first frame already wears the stored Light/Dark choice: the repository reads it synchronously.
            val appearance by koinInject<ParentRepository>().appearance.collectAsState()
            CompositionLocalProvider(LocalDarkTheme provides appearance.isDark(isSystemInDarkTheme())) { AcademicTheme { AnimatedLoadingView("…") } }
            return@KoinContext
        }
        // M4 (D3): back in front — whatever was kept offline is tried again at once, whichever screen is showing.
        val answers: PendingAnswersSync = koinInject()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { answers.nudge() }
        val nav = rememberNavController()
        val start: Any = if (auth.state.value is AuthState.SignedIn) Routes.WorldMap else Routes.SignIn
        // Everything below sees the joined school's colours, name, logo and feature flags (§3, §4).
        SchoolThemeHost {
            AppLockHost(onSignedOut = { nav.navigate(Routes.SignIn) { popUpTo(0) { inclusive = true } } }) { QuestNavHost(nav, start) }
        }
    }
}

@Composable
fun QuestNavHost(nav: NavHostController, start: Any) {
    val lock: AppLock = koinInject()
    val pushRegistration: PushRegistration = koinInject()
    val scope = rememberCoroutineScope()   // outlives the sign-in screen, which is popped the moment it succeeds
    // M3: a tap on the home-screen widget. The navigation happens underneath the lock's cover, so a locked app still
    // asks for the biometric first and then shows where the tap pointed. Signed out, the tap just opens the app.
    val link by TodayLinks.pending.collectAsState()
    val auth: AuthProvider = koinInject()
    LaunchedEffect(link) {
        val target = link ?: return@LaunchedEffect
        TodayLinks.consumed()
        if (auth.state.value !is AuthState.SignedIn) return@LaunchedEffect
        when (target) {
            TodayLink.HOME -> nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } }
            TodayLink.MESSAGES -> nav.navigate(Routes.ParentPin())      // messages are the parent's: her gate comes first
        }
    }
    // M5: a tapped push. Like the widget, it is followed underneath the lock; a parent-area destination also goes
    // through the parent gate, and only after it opens is the conversation (or Progress, or the feed) opened on top of
    // the parent home. A link that leads nowhere any more — her child unlinked, the thread gone — ends on that home.
    // Collected, not keyed: consuming the tap changes the flow, and a keyed effect would be cancelled mid-way by it.
    val pushes: PushNavigator = koinInject()
    LaunchedEffect(Unit) {
        PushLinks.pending.filterNotNull().collect { open ->
            PushLinks.consumed()
            when (val route = pushes.beforeGate(open)) {
                null -> Unit
                Routes.WorldMap -> nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } }
                else -> nav.navigate(route)
            }
        }
    }
    LaunchedEffect(Unit) {
        PushLinks.afterGate.filterNotNull().collect { link ->
            PushLinks.followed()
            pushes.afterGate(link)?.let { nav.navigate(it) }
        }
    }
    NavHost(navController = nav, startDestination = start) {
        // After sign-in the parent sees every child the school linked to the account, and picks whose home to open.
        composable<Routes.SignIn> {
            // M2: a password sign-in is followed, once, by the offer to unlock with a biometric from now on.
            SignInRoute(onSignedIn = { scope.launch { lock.signedIn() }; scope.launch { pushRegistration.signedIn() }; nav.navigate(Routes.ChildPicker) { popUpTo(Routes.SignIn) { inclusive = true } } })
        }
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
                    onOpenResult = { nav.navigate(Routes.ExamResult(it)) },
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
        composable<Routes.ExamResult> { entry ->
            val r = entry.toRoute<Routes.ExamResult>()
            LessonTheme { ExamResultRoute(r.lessonId, onBack = { nav.popBackStack() }) }
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
