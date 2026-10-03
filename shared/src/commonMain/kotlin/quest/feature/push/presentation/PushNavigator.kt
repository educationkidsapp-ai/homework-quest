package quest.feature.push.presentation

import quest.core.navigation.Routes
import quest.feature.parent.presentation.asConversation
import quest.feature.push.domain.Destination
import quest.feature.push.domain.NotificationRouter
import quest.feature.push.domain.NotificationTap
import quest.feature.push.domain.ParentGate

/**
 * M5 — where a tap navigates, for a push and for a row of the Notifications tab alike (the owner, 2026-10-03):
 *
 * 1. **The parent gate first.** A tap from the system shade always meets it; a row tapped inside the parent area skips
 *    it while the gate is still open ([ParentGate]). Otherwise the tap waits behind
 *    `Routes.ParentPin` — the biometric where the parent turned it on (M2), the PIN otherwise. Backing out of the gate
 *    drops the tap; the target is never shown. The app's own biometric lock sits over all of it, as for the widget.
 * 2. **Then the specific page**, found by [NotificationRouter]: [Step.Parent] opens on top of the parent home, and
 *    [Step.Child] replaces the stack with the child's home (an exam's card is hers).
 */
class PushNavigator(private val router: NotificationRouter, private val gate: ParentGate, private val signedIn: () -> Boolean) {
    sealed interface Step {
        /** Not signed in: the tap only opened the app. */
        data object Nothing : Step
        /** The parent gate, with the tap waiting behind it. */
        data object Gate : Step
        data class Parent(val route: Any) : Step
        /** [route] null: the child's home itself. */
        data class Child(val route: Any?) : Step
    }

    /** A tap arrived. */
    suspend fun follow(tap: NotificationTap): Step = when {
        !signedIn() -> Step.Nothing
        tap.outside || !gate.isOpen -> Step.Gate
        else -> step(router.resolve(tap))
    }

    /** The gate opened with [tap] waiting. */
    suspend fun afterGate(tap: NotificationTap): Step = step(router.resolve(tap))

    companion object {
        fun step(destination: Destination): Step = when (destination) {
            is Destination.Conversation -> Step.Parent(destination.thread.asConversation())
            is Destination.Complaint -> Step.Parent(Routes.Complaint(destination.childId, destination.complaintId))
            is Destination.WeeklyPlan -> Step.Parent(Routes.WeeklyPlan(focus = destination.planId))
            is Destination.Broadcast -> Step.Parent(Routes.Broadcasts(focusBroadcast = destination.broadcastId))
            is Destination.Notifications -> Step.Parent(Routes.Broadcasts(focusRow = destination.row, gone = destination.gone))
            Destination.Progress -> Step.Parent(Routes.Progress())
            // The owner (2026-10-03): the parent's own view of the lesson and of the result — inside the parent area.
            is Destination.Lesson -> Step.Parent(Routes.LessonPanel(destination.lessonId))
            Destination.ExamCard -> Step.Child(null)
            is Destination.ExamResult -> Step.Parent(Routes.Progress(focusExam = destination.lessonId))
        }
    }
}
