package quest.core.navigation

import kotlinx.serialization.Serializable

/** Type-safe routes for Compose Navigation. Child and parent graphs only meet at the PIN. */
object Routes {
    @Serializable object SignIn
    @Serializable object ChildPicker

    // child mode
    @Serializable object WorldMap
    @Serializable data class Journey(val lessonId: String, val level: Int = 1, val variant: Int = 0)
    @Serializable data class StopPlayer(val lessonId: String, val level: Int, val variant: Int, val index: Int)
    @Serializable data class LessonComplete(val lessonId: String, val level: Int, val variant: Int)
    /** M4 (D4): an exam's released result. */
    @Serializable data class ExamResult(val lessonId: String)

    // parent mode
    /** [push] (M5): a tapped notification waits behind this gate (`PushLinks.awaiting`) and is followed once it opens. */
    @Serializable data class ParentPin(val lessonId: String? = null, val push: Boolean = false)
    @Serializable object ParentHome
    @Serializable object Calendar
    /** [focusExam] (M5): a released exam a tapped notification is about, shown first and outlined. */
    @Serializable data class Progress(val focusExam: String? = null)
    @Serializable object Settings
    @Serializable object ChangePin
    @Serializable data class LessonPanel(val lessonId: String)
    @Serializable object ChatThreads

    /** RM4: the broadcasts feed — this week's plan, announcements and events for the current child. */
    @Serializable data class Broadcasts(
        /** M5: an announcement or event to open (a tapped push or row). */
        val focusBroadcast: String? = null,
        /** M5: a notification row to open and highlight. */
        val focusRow: String? = null,
        /** M5: the tapped item no longer exists — said in one line above the list. */
        val gone: Boolean = false,
    )
    /** MH3: the weekly-plan archive, the other half of what RM4 called School news. */
    /** [focus] (M5): the plan a tapped push or row is about, opened on arrival. */
    @Serializable data class WeeklyPlan(val focus: String? = null)

    /** R8: the coordinators of the current child's section, where a parent starts a thread with one of them. */
    @Serializable object ChatCoordinators

    /**
     * R8 widened this with what the list row already knew, so the conversation draws its header without a second
     * request. [staffRole] is the contract's own spelling (`TEACHER`/`COORDINATOR`/`MANAGERIAL`).
     */
    @Serializable data class ChatConversation(
        val childId: String,
        val teacherId: String,
        val teacherName: String,
        val staffRole: String = "TEACHER",
        val subject: String? = null,
        val threadId: String? = null,
        /** S1: a thread the school administration opened (`ChatThread.withAdmin`). */
        val admin: Boolean = false,
        /** M4 (D6): `ChatThread.peerOnline` as `"true"`/`"false"`, null when the row said nothing. */
        val peerOnline: String? = null,
        /** M4 (D7): `ChatThread.peerRole`'s wire name, null when absent — it names who is typing (M7). */
        val peerRole: String? = null,
    )

    /** M8: the Complaints tab — her complaints about the current child, apart from Messages. */
    @Serializable object Complaints

    /** M8: New complaint — the child, whom it is for, a subject line and the first message. */
    @Serializable object NewComplaint

    /** M8: one complaint, its messages and its status changes (`/children/{childId}/complaints/{complaintId}`). */
    @Serializable data class Complaint(val childId: String, val complaintId: String)
}
