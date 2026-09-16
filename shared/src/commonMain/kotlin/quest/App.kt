package quest

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
import quest.feature.children.presentation.AddChildRoute
import quest.feature.children.presentation.ChildPickerRoute
import quest.feature.journey.presentation.JourneyRoute
import quest.feature.journey.presentation.LessonCompleteRoute
import quest.feature.journey.presentation.LoadingView
import quest.feature.journey.presentation.StopPlayerRoute
import quest.feature.map.presentation.WorldMapRoute
import quest.feature.parent.presentation.parentGraph
import quest.feature.rewards.presentation.StickerBookRoute
import quest.feature.rewards.presentation.TreasureChestRoute
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.feature.school.presentation.SchoolThemeHost
import quest.ui.design.ChildTheme

/** Root of the shared UI. Koin must already be started by the platform entry point. */
@Composable
fun App() {
    KoinContext {
        val initializer: AppInitializer = koinInject()
        val auth: AuthProvider = koinInject()
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { initializer.initialise(); ready = true }
        if (!ready) { ChildTheme { LoadingView("Waking Pip up…") }; return@KoinContext }
        val nav = rememberNavController()
        val start: Any = if (auth.state.value is AuthState.SignedIn) Routes.WorldMap else Routes.SignIn
        // Everything below sees the joined school's colours, name, logo and feature flags (§3, §4).
        SchoolThemeHost { QuestNavHost(nav, start) }
    }
}

@Composable
fun QuestNavHost(nav: NavHostController, start: Any) {
    NavHost(navController = nav, startDestination = start) {
        composable<Routes.SignIn> { SignInRoute(onSignedIn = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.SignIn) { inclusive = true } } }) }
        composable<Routes.AddChild> { entry ->
            val editingId = entry.toRoute<Routes.AddChild>().editingId
            AddChildRoute(editingId, onSaved = { if (editingId == null) nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } else nav.popBackStack() }, onBack = if (editingId == null) null else ({ nav.popBackStack() }))
        }
        composable<Routes.ChildPicker> { ChildPickerRoute(onPicked = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } }, onAdd = { nav.navigate(Routes.AddChild()) }, onBack = { nav.popBackStack() }) }
        composable<Routes.WorldMap> {
            ChildTheme {
                WorldMapRoute(
                    onSwitchChild = { nav.navigate(Routes.ChildPicker) },
                    onOpenLesson = { id, level, variant -> nav.navigate(Routes.Journey(id, level, variant)) },
                    onStickers = { nav.navigate(Routes.StickerBook) }, onChest = { nav.navigate(Routes.TreasureChest) },
                    onGrownUps = { nav.navigate(Routes.ParentPin()) },
                    onNeedsChild = { nav.navigate(Routes.AddChild()) { popUpTo(Routes.WorldMap) { inclusive = true } } },
                )
            }
        }
        composable<Routes.Journey> { entry ->
            val r = entry.toRoute<Routes.Journey>()
            ChildTheme {
                JourneyRoute(r.lessonId, r.level, r.variant,
                    onOpenStop = { id, level, variant, index -> nav.navigate(Routes.StopPlayer(id, level, variant, index)) },
                    onComplete = { id, level, variant -> nav.navigate(Routes.LessonComplete(id, level, variant)) },
                    onParentPanel = { nav.navigate(Routes.ParentPin(it)) }, onBack = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } })
            }
        }
        composable<Routes.StopPlayer> { entry ->
            val r = entry.toRoute<Routes.StopPlayer>()
            ChildTheme {
                StopPlayerRoute(r.lessonId, r.level, r.variant, r.index,
                    onFinished = { id, level, variant -> nav.navigate(Routes.LessonComplete(id, level, variant)) { popUpTo(Routes.Journey(id, level, variant)) { inclusive = true } } },
                    onBack = { nav.popBackStack() })
            }
        }
        composable<Routes.LessonComplete> { entry ->
            val r = entry.toRoute<Routes.LessonComplete>()
            ChildTheme {
                LessonCompleteRoute(r.lessonId, r.level, r.variant,
                    onAgain = { id, level, variant -> nav.navigate(Routes.Journey(id, level, variant)) { popUpTo(Routes.WorldMap) } },
                    onNextLevel = { id, level -> nav.navigate(Routes.Journey(id, level, 0)) { popUpTo(Routes.WorldMap) } },
                    onStickers = { nav.navigate(Routes.StickerBook) },
                    onMap = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } })
            }
        }
        composable<Routes.StickerBook> { ChildTheme { StickerBookRoute(onBack = { nav.popBackStack() }) } }
        composable<Routes.TreasureChest> {
            ChildTheme {
                FeatureGate(Flags.TREASURE_CHEST) { TreasureChestRoute(onBack = { nav.popBackStack() }) }
                GateFallback(Flags.TREASURE_CHEST) { nav.popBackStack() }
            }
        }
        parentGraph(nav)
    }
}
