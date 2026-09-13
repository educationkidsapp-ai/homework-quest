package quest.feature.parent.presentation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import quest.core.design.ParentTheme
import quest.core.navigation.Routes
import quest.feature.practice.presentation.ErrorView

/** Parent-mode graph. Everything here is behind the PIN (Phase 4). */
fun NavGraphBuilder.parentGraph(nav: NavHostController) {
    composable<Routes.ParentPin> {
        ParentTheme(rtl = false) { ErrorView("Parent mode arrives in Phase 4.", onBack = { nav.popBackStack() }) }
    }
}
