package quest.core.navigation

import kotlinx.serialization.Serializable

/** Type-safe routes for Compose Navigation. Child and parent graphs only meet at the PIN. */
object Routes {
    @Serializable object SignIn
    @Serializable data class AddChild(val editingId: String? = null)
    @Serializable object ChildPicker

    // child mode
    @Serializable object WorldMap
    @Serializable data class Journey(val lessonId: String, val level: Int = 1, val variant: Int = 0)
    @Serializable data class StopPlayer(val lessonId: String, val level: Int, val variant: Int, val index: Int)
    @Serializable data class LessonComplete(val lessonId: String, val level: Int, val variant: Int)

    // parent mode
    @Serializable data class ParentPin(val lessonId: String? = null)
    @Serializable object ParentHome
    @Serializable object Calendar
    @Serializable object Progress
    @Serializable object Settings
    @Serializable object ChangePin
    @Serializable data class LessonPanel(val lessonId: String)
    @Serializable object ChatThreads

    /** RM4: the broadcasts feed — this week's plan, announcements and events for the current child. */
    @Serializable object Broadcasts
    /** MH3: the weekly-plan archive, the other half of what RM4 called School news. */
    @Serializable object WeeklyPlan

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
    )
}
