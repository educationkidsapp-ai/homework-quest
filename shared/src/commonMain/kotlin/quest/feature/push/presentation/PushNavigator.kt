package quest.feature.push.presentation

import quest.core.navigation.Routes
import quest.feature.parent.presentation.asConversation
import quest.feature.push.domain.FollowPushUseCase
import quest.feature.push.domain.PushOpen
import quest.feature.push.domain.PushTarget
import quest.feature.push.domain.pushTarget

/**
 * M5: where a tapped push navigates, in two legs, so neither the biometric lock nor the parent area's gate is ever
 * walked around. The app's root navigates underneath the lock's cover (as for the widget), so a locked app asks for the
 * biometric first whatever the leg.
 *
 * 1. [beforeGate]: the child's home for a homework (no gate — it is the child's own screen); otherwise the parent
 *    gate, carrying the link. A signed-out app goes nowhere.
 * 2. [afterGate]: once the gate has opened onto the parent home, the screen on top of it — the conversation, Progress
 *    or the school's news — or nothing when the thread is gone, which leaves the parent on her home without an error.
 */
class PushNavigator(private val follow: FollowPushUseCase, private val signedIn: () -> Boolean) {
    suspend fun beforeGate(open: PushOpen): Any? {
        if (!signedIn()) return null
        return when (follow.prepare(open)) {
            is PushTarget.ChildHome -> Routes.WorldMap
            PushTarget.ParentHome -> Routes.ParentPin()
            else -> Routes.ParentPin(push = open.link)
        }
    }

    suspend fun afterGate(link: String): Any? = when (val target = pushTarget(link)) {
        is PushTarget.Conversation -> follow.thread(target)?.asConversation()
        is PushTarget.Progress -> Routes.Progress
        is PushTarget.Broadcasts -> Routes.Broadcasts
        is PushTarget.ChildHome, PushTarget.ParentHome -> null
    }
}
