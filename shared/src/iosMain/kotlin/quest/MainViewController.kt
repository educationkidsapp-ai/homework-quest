package quest

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController
import quest.di.ApiConfig
import quest.di.appModules

private var koinStarted = false

/** Entry point called from SwiftUI. `API_BASE_URL` in Info.plist selects the server; empty = seeded fake API. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController {
    if (!koinStarted) {
        val url = NSBundle.mainBundle.objectForInfoDictionaryKey("API_BASE_URL") as? String
        val config = if (url.isNullOrBlank()) ApiConfig.Fake() else ApiConfig.Server(url)
        startKoin { modules(appModules(config)) }
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
