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
    @Serializable object StickerBook
    @Serializable object TreasureChest

    // parent mode
    @Serializable object ParentPin
    @Serializable object ParentHome
    @Serializable object Calendar
    @Serializable object Progress
    @Serializable object Settings
    @Serializable object ChangePin
    @Serializable data class LessonPanel(val lessonId: String)
}
