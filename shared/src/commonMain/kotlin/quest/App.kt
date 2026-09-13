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
import quest.core.design.ChildTheme
import quest.core.navigation.Routes
import quest.core.platform.Today
import quest.di.ApiConfig
import quest.di.AppInitializer
import quest.di.DemoSeeder
import quest.feature.map.presentation.LessonIntroRoute
import quest.feature.map.presentation.WelcomeRoute
import quest.feature.map.presentation.WorldMapRoute
import quest.feature.parent.presentation.parentGraph
import quest.feature.practice.presentation.LoadingView
import quest.feature.practice.presentation.PracticeRoute
import quest.feature.rewards.presentation.StickerBookRoute
import quest.feature.rewards.presentation.TreasureChestRoute

/** Root of the shared UI. Koin must already be started by the platform entry point. */
@Composable
fun App() {
    KoinContext {
        val initializer: AppInitializer = koinInject()
        val config: ApiConfig = koinInject()
        val seeder: DemoSeeder = koinInject()
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            initializer.initialise()
            ready = true
            if (config is ApiConfig.Fake && config.seedDemo) runCatching { seeder.seedIfEmpty(Today.date()) }
        }
        if (!ready) { ChildTheme { LoadingView("Waking Pip up…") }; return@KoinContext }
        val nav = rememberNavController()
        QuestNavHost(nav)
    }
}

@Composable
fun QuestNavHost(nav: NavHostController) {
    NavHost(navController = nav, startDestination = Routes.Welcome) {
        composable<Routes.Welcome> {
            ChildTheme { WelcomeRoute(onPlay = { nav.navigate(Routes.WorldMap) }, onGrownUps = { nav.navigate(Routes.ParentPin) }) }
        }
        composable<Routes.WorldMap> {
            ChildTheme {
                WorldMapRoute(
                    onOpenIntro = { nav.navigate(Routes.LessonIntro(it)) },
                    onStickers = { nav.navigate(Routes.StickerBook) },
                    onChest = { nav.navigate(Routes.TreasureChest) },
                    onGrownUps = { nav.navigate(Routes.ParentPin) },
                )
            }
        }
        composable<Routes.LessonIntro> { entry ->
            val route = entry.toRoute<Routes.LessonIntro>()
            ChildTheme { LessonIntroRoute(route.skillId, onOpenSet = { nav.navigate(Routes.Practice(it)) { popUpTo(Routes.WorldMap) } }, onBack = { nav.popBackStack() }) }
        }
        composable<Routes.Practice> { entry ->
            val route = entry.toRoute<Routes.Practice>()
            ChildTheme {
                PracticeRoute(
                    route.setId,
                    onOpenSet = { nav.navigate(Routes.Practice(it)) { popUpTo(Routes.WorldMap) } },
                    onMap = { nav.navigate(Routes.WorldMap) { popUpTo(Routes.WorldMap) { inclusive = true } } },
                    onStickers = { nav.navigate(Routes.StickerBook) },
                )
            }
        }
        composable<Routes.StickerBook> { ChildTheme { StickerBookRoute(onBack = { nav.popBackStack() }) } }
        composable<Routes.TreasureChest> { ChildTheme { TreasureChestRoute(onBack = { nav.popBackStack() }) } }
        parentGraph(nav)
    }
}
