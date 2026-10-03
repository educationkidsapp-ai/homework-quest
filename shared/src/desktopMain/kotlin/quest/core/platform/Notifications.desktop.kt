package quest.core.platform

import androidx.compose.runtime.Composable

/** M5: no push on the desktop app, so nothing to ask for. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission = NoNotificationPermission
