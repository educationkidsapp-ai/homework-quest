package quest.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import quest.admin.resources.Res
import quest.admin.resources.notocoloremoji
import quest.ui.resources.ibmplexsansarabic_regular
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.savedstate.read
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import quest.admin.core.design.AdminShell
import quest.admin.core.design.AdminTheme
import quest.admin.di.Graph
import quest.admin.feature.auth.presentation.SignInContract
import quest.admin.feature.auth.presentation.SignInScreen
import quest.admin.feature.auth.presentation.SignInViewModel
import quest.admin.feature.editor.presentation.LessonScreen
import quest.admin.feature.editor.presentation.LessonViewModel
import quest.admin.feature.lessons.presentation.CalendarScreen
import quest.admin.feature.lessons.presentation.LessonsScreen
import quest.admin.feature.lessons.presentation.LessonsViewModel
import quest.admin.feature.lessons.presentation.NewLessonScreen
import quest.admin.feature.lessons.presentation.NewLessonViewModel
import quest.admin.feature.reports.presentation.CacheScreen
import quest.admin.feature.reports.presentation.ReportsViewModel
import quest.admin.feature.reports.presentation.UsageScreen

/** The browser canvas has no system fallback fonts: preload Arabic (IBM Plex Sans Arabic) and Noto Color Emoji as fallbacks for every text. */
@OptIn(org.jetbrains.compose.resources.ExperimentalResourceApi::class)
@Composable
private fun EmojiFallback(content: @Composable () -> Unit) {
    val emoji by org.jetbrains.compose.resources.preloadFont(Res.font.notocoloremoji)
    val arabic by org.jetbrains.compose.resources.preloadFont(quest.ui.resources.Res.font.ibmplexsansarabic_regular)
    val resolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    var ready by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(emoji, arabic) { val e = emoji; val a = arabic; if (e != null && a != null) { resolver.preload(androidx.compose.ui.text.font.FontFamily(e)); resolver.preload(androidx.compose.ui.text.font.FontFamily(a)); ready = true } }
    if (ready) content()
}

@Composable
fun AdminApp() = EmojiFallback { AdminAppContent() }

@Composable
private fun AdminAppContent() {
    AdminTheme {
        val session by Graph.session.session.collectAsState()
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val route = entry?.destination?.route ?: "lessons"
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        if (session == null || session!!.expiresAt < Clock.System.now().toEpochMilliseconds()) {
            val vm = viewModel { SignInViewModel(Graph.api, Graph.session) }
            LaunchedEffect(vm) { vm.effects.collect { if (it is SignInContract.Effect.SignedIn) Unit } }
            SignInScreen(vm, Graph.apiBaseUrl)
            return@AdminTheme
        }
        AdminShell(route, session?.email, Graph.apiBaseUrl, onNavigate = { nav.navigate(it) { launchSingleTop = true; popUpTo("lessons") } }, onSignOut = { Graph.session.clear() }) {
            NavHost(nav, startDestination = "lessons") {
                composable("lessons") { val vm = viewModel { LessonsViewModel(Graph.api, today, session?.email) }; LessonsScreen(vm, onOpen = { nav.navigate("lesson/$it") }, onNew = { nav.navigate("new") }) }
                composable("calendar") { val vm = viewModel { LessonsViewModel(Graph.api, today, session?.email) }; CalendarScreen(vm, onOpenDay = { nav.navigate("lessons") { launchSingleTop = true } }) }
                composable("new") { val vm = viewModel { NewLessonViewModel(Graph.api, today, session?.email) }; NewLessonScreen(vm, onCreated = { nav.navigate("lesson/$it") { popUpTo("lessons") } }) }
                composable("lesson/{id}") { back ->
                    val id = back.arguments?.read { getString("id") } ?: return@composable
                    val vm = viewModel(key = id) { LessonViewModel(id, Graph.api) }
                    LessonScreen(vm, onBack = { nav.navigate("lessons") { popUpTo("lessons") { inclusive = true } } })
                }
                composable("cache") { val vm = viewModel { ReportsViewModel(Graph.api) }; CacheScreen(vm, onOpenLesson = { nav.navigate("lesson/$it") }) }
                composable("usage") { val vm = viewModel { ReportsViewModel(Graph.api) }; UsageScreen(vm, onOpenLesson = { nav.navigate("lesson/$it") }) }
            }
        }
    }
}
