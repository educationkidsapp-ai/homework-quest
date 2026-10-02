package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import platform.UIKit.UIUserInterfaceStyle
import platform.UIKit.UIViewController

/**
 * iOS draws the status bar for the window's interface style, so the window is told which one the app is wearing —
 * which also makes system sheets (the document preview, the keyboard) match a Light or Dark choice made in the app.
 *
 * "System" sets the style back to *unspecified*: the window then inherits the device's style again, follows it when
 * the device switches, and `isSystemInDarkTheme()` — which reads this window's trait collection — reports the device
 * rather than the app's last override.
 */
@Composable
actual fun SystemBarsAppearance(dark: Boolean, followsSystem: Boolean) {
    LaunchedEffect(dark, followsSystem) { InterfaceStyle.apply(interfaceStyle(dark, followsSystem)) }
}

/** The override for a Settings choice: none for System, else the pinned style. */
fun interfaceStyle(dark: Boolean, followsSystem: Boolean): UIUserInterfaceStyle = when {
    followsSystem -> UIUserInterfaceStyle.UIUserInterfaceStyleUnspecified
    dark -> UIUserInterfaceStyle.UIUserInterfaceStyleDark
    else -> UIUserInterfaceStyle.UIUserInterfaceStyleLight
}

/**
 * Where the style is set. [root] is the Compose view controller, styled when it is created — before it is on screen,
 * and before there may be a window to style — so the first frame is already in the stored choice. Later changes go
 * to both: an override left on the controller would otherwise outrank the window's.
 */
object InterfaceStyle {
    var root: UIViewController? = null

    fun apply(style: UIUserInterfaceStyle) {
        root?.overrideUserInterfaceStyle = style
        activeKeyWindow()?.overrideUserInterfaceStyle = style
    }
}
