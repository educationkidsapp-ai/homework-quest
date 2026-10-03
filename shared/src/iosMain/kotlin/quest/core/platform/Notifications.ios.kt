package quest.core.platform

import androidx.compose.runtime.Composable

/** M5: no push on this platform yet (an Apple developer account comes first), so nothing to ask for. */
@Composable
actual fun rememberNotificationPermission(): NotificationPermission = NoNotificationPermission
