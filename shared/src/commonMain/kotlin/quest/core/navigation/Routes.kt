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
     * R8 widened this with what the list row already knew, so the conversation draws its header, its complaint
     * toggle and its resolved banner without a second request. [staffRole] and [topic] are the contract's own
     * spellings (`TEACHER`/`COORDINATOR`, `question`/`complaint`); the `status` frame moves [resolved] afterwards.
     */
    @Serializable data class ChatConversation(
        val childId: String,
        val teacherId: String,
        val teacherName: String,
        val staffRole: String = "TEACHER",
        val subject: String? = null,
        val topic: String = "question",
        val resolved: Boolean = false,
        val threadId: String? = null,
        /** M1: opened from New message with "Complaint" chosen — the toggle starts on. */
        val complaint: Boolean = false,
        /** S1: a thread the school administration opened (`ChatThread.withAdmin`). */
        val admin: Boolean = false,
        /** M4 (D6): `ChatThread.peerOnline` as `"true"`/`"false"`, null when the row said nothing. */
        val peerOnline: String? = null,
        /** M4 (D7): `ChatThread.peerRole`'s wire name, null when absent. */
        val peerRole: String? = null,
    )
}
