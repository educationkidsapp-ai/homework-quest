package quest.core.platform

import androidx.compose.runtime.Composable

/**
 * M5: the system's permission to post notifications. [needed] is false where the platform does not ask (Android before
 * 13, and iOS / desktop, which have no push yet) — the app then never shows the "Turn on notifications" card.
 */
interface NotificationPermission {
    val needed: Boolean
    fun granted(): Boolean
    /** Shows the system dialog; true when granted. */
    suspend fun request(): Boolean
}

object NoNotificationPermission : NotificationPermission {
    override val needed = false
    override fun granted() = true
    override suspend fun request() = true
}

@Composable
expect fun rememberNotificationPermission(): NotificationPermission
