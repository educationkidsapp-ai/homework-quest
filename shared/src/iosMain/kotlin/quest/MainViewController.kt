@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package quest

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController
import quest.di.ApiConfig
import quest.di.appModules

private var koinStarted = false
private var hookInstalled = false

/** Entry point called from SwiftUI. `API_BASE_URL` in Info.plist selects the server; empty = seeded fake API. */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController {
    // XCUITest waits for the app to go idle before every event; looping animations never let it.
    if (platform.Foundation.NSProcessInfo.processInfo.environment["QUEST_UI_TEST"] != null) quest.ui.design.Motion.reduced = true
    // Uncaught Kotlin exceptions abort the process; NSLog puts their message and stack in the unified log first,
    // so `log show --predicate 'eventMessage CONTAINS "QUEST UNCAUGHT"'` explains a crash report.
    if (!hookInstalled) {
        setUnhandledExceptionHook { e: Throwable ->
            platform.Foundation.NSLog("QUEST UNCAUGHT %@", e.stackTraceToString())
            terminateWithUnhandledException(e)
        }
        hookInstalled = true
    }
    if (!koinStarted) {
        val url = NSBundle.mainBundle.objectForInfoDictionaryKey("API_BASE_URL") as? String
        val config = if (url.isNullOrBlank()) ApiConfig.Fake else ApiConfig.Server(url)
        startKoin { modules(appModules(config)) }
        koinStarted = true
    }
    return ComposeUIViewController { App() }
}
