package quest.core.platform

import androidx.compose.runtime.Composable

/** A desktop window has no status bar to tint. */
@Composable
actual fun SystemBarsAppearance(dark: Boolean, followsSystem: Boolean) = Unit
