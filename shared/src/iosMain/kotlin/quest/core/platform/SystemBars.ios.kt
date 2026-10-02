package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import platform.UIKit.UIUserInterfaceStyle

/**
 * iOS draws the status bar for the window's interface style, so the window is told which one the app is wearing —
 * which also makes system sheets (the document preview, the keyboard) match a Light or Dark choice made in the app.
 */
@Composable
actual fun SystemBarsAppearance(dark: Boolean) {
    LaunchedEffect(dark) {
        activeKeyWindow()?.overrideUserInterfaceStyle =
            if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
    }
}
