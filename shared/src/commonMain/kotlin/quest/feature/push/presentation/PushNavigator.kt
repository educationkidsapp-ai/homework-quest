package quest.feature.push.presentation

import quest.core.navigation.Routes
import quest.feature.parent.presentation.asConversation
import quest.feature.push.domain.Destination
import quest.feature.push.domain.isAttendance
import quest.feature.push.domain.NotificationRouter
import quest.feature.push.domain.NotificationTap
import quest.feature.push.domain.ParentGate
import quest.feature.push.domain.StaffAttendanceTarget

/**
 * One step of following a tapped push or notification row.
 *
 * Feature 13: a staff tap takes a different road through this. A parent's tap waits behind the parent gate (Face ID
 * or the PIN) because the parent area is behind it; a staff member has no such area — her whole app is the staff app
 * — so hers waits behind the **app lock** instead, which is what [awaitUnlocked] is, and then opens the conversation.
 */
class PushNavigator(
    private val router: NotificationRouter,
    private val gate: ParentGate,
    private val signedIn: () -> Boolean,
    /** Feature 13: returns once the app is not locked (`AppLock.awaitUnlocked`). */
    private val awaitUnlocked: suspend () -> Unit = {},
) {
    sealed interface Step {
        data object Nothing : Step
        data object Gate : Step
        data class Parent(val route: Any) : Step
        data class Child(val route: Any?) : Step
        /** Feature 13: a staff screen, opened once the app lock is past. */
        data class Staff(val route: Any) : Step
    }

    suspend fun follow(tap: NotificationTap): Step {
        if (!signedIn()) return Step.Nothing
        // A tap that names a thread may be a staff member's. Resolving it is also what rules it out: a thread that is
        // not one of hers answers null and the parent road below is taken, gate and all, exactly as before.
        if (tap.threadId != null) {
            val destination = router.resolve(tap)
            if (destination is Destination.StaffConversation) { awaitUnlocked(); return step(destination) }
            return if (tap.outside || !gate.isOpen) Step.Gate else step(destination)
        }
        // Feature 16: an attendance tap is a staff member's too, and names no thread. The same road: the app lock, then
        // her Attendance tab or her notifications. Without a staff session it falls through to the parent gate.
        if (isAttendance(tap.kind)) {
            val destination = router.resolve(tap)
            if (destination is Destination.StaffAttendance) { awaitUnlocked(); return step(destination) }
            return if (tap.outside || !gate.isOpen) Step.Gate else step(destination)
        }
        if (tap.outside || !gate.isOpen) return Step.Gate
        return step(router.resolve(tap))
    }

    suspend fun afterGate(tap: NotificationTap): Step = step(router.resolve(tap))

    companion object {
        fun step(destination: Destination): Step = when (destination) {
            is Destination.Conversation -> Step.Parent(destination.thread.asConversation())
            is Destination.StaffConversation -> Step.Staff(Routes.StaffConversation(threadId = destination.thread.id.orEmpty()))
            is Destination.StaffAttendance -> Step.Staff(
                when (destination.target) {
                    StaffAttendanceTarget.HOME -> Routes.StaffHome
                    StaffAttendanceTarget.NOTIFICATIONS -> Routes.StaffNotifications
                    StaffAttendanceTarget.MY_RECORD -> Routes.StaffMyRecord
                },
            )
            is Destination.Complaint -> Step.Parent(Routes.Complaint(destination.childId, destination.complaintId))
            is Destination.WeeklyPlan -> Step.Parent(Routes.WeeklyPlan(focus = destination.planId))
            is Destination.Broadcast -> Step.Parent(Routes.Broadcasts(focusBroadcast = destination.broadcastId))
            is Destination.Notifications -> Step.Parent(Routes.Broadcasts(focusRow = destination.row, gone = destination.gone))
            Destination.Progress -> Step.Parent(Routes.Progress())
            is Destination.Lesson -> Step.Parent(Routes.LessonPanel(destination.lessonId))
            Destination.ExamCard -> Step.Child(null)
            is Destination.ExamResult -> Step.Parent(Routes.Progress(focusExam = destination.lessonId))
        }
    }
}
