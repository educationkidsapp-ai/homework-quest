package quest.core.platform

import platform.UIKit.UIApplication
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene

/**
 * The key window of the scene the user is looking at. `UIApplication.keyWindow` is deprecated since iOS 13 and wrong
 * with more than one scene; this asks the foreground-active `UIWindowScene` for its key window instead, and falls back
 * to that scene's first window while none is key yet.
 */
internal fun activeKeyWindow(): UIWindow? {
    val scenes = UIApplication.sharedApplication.connectedScenes.filterIsInstance<UIWindowScene>()
    val scene = scenes.firstOrNull { it.activationState == UISceneActivationStateForegroundActive } ?: scenes.firstOrNull() ?: return null
    val windows = scene.windows.filterIsInstance<UIWindow>()
    return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
}
