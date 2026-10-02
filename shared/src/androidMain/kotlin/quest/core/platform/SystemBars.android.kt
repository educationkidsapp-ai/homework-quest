package quest.core.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import android.app.UiModeManager
import android.os.Build
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
actual fun SystemBarsAppearance(dark: Boolean, followsSystem: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    // The choice is handed to the system as well (API 31+), which keeps it for the next cold start: the splash
    // screen and the window background are then drawn in the chosen palette before any of this code runs.
    LaunchedEffect(dark, followsSystem) { applyNightMode(view.context, dark, followsSystem) }
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Tells Android which night mode this app wears, so everything the *system* draws for it — the splash screen, the
 * starting window — matches the Settings choice. `MODE_NIGHT_AUTO` here is "no app override": the device decides.
 * Before API 31 there is no per-app night mode; `MainActivity` picks the window theme itself there.
 */
fun applyNightMode(context: Context, dark: Boolean, followsSystem: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val mode = when { followsSystem -> UiModeManager.MODE_NIGHT_AUTO; dark -> UiModeManager.MODE_NIGHT_YES; else -> UiModeManager.MODE_NIGHT_NO }
    (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.setApplicationNightMode(mode)
}
