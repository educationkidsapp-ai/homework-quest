@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package quest

import androidx.compose.ui.window.ComposeUIViewController
import org.koin.core.context.startKoin
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController
import quest.di.ApiConfig
import quest.di.appModules
import quest.core.platform.InterfaceStyle
import quest.core.platform.interfaceStyle
import quest.feature.parent.domain.Appearance
import quest.feature.parent.domain.ParentRepository

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
        val firebaseKey = NSBundle.mainBundle.objectForInfoDictionaryKey("FIREBASE_API_KEY") as? String
        val config = if (url.isNullOrBlank()) ApiConfig.Fake else ApiConfig.Server(url, firebaseKey.orEmpty())
        startKoin { modules(appModules(config)) }
        koinStarted = true
    }
    // The stored Light/Dark choice is on the controller before it is shown, so the first frame is not the device's.
    val appearance = org.koin.mp.KoinPlatform.getKoin().get<ParentRepository>().appearance.value
    return ComposeUIViewController { App() }.also {
        InterfaceStyle.root = it
        InterfaceStyle.apply(interfaceStyle(dark = appearance == Appearance.DARK, followsSystem = appearance == Appearance.SYSTEM))
    }
}
