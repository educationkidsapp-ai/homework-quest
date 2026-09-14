package quest.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.koin.core.context.startKoin
import quest.App
import quest.di.ApiConfig
import quest.di.appModules

/** Desktop runner for fast local iteration; renders the same shared UI in the 412×915 Android frame. */
fun main() {
    val useFake = System.getenv("QUEST_API_URL").isNullOrBlank()
    val config = if (useFake) ApiConfig.Fake else ApiConfig.Server(System.getenv("QUEST_API_URL"), System.getenv("QUEST_FIREBASE_API_KEY").orEmpty())
    startKoin { modules(appModules(config)) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Homework Quest", state = rememberWindowState(width = 412.dp, height = 915.dp)) {
            App()
        }
    }
}
