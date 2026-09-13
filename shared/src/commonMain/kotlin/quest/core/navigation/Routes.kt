package quest.core.navigation

import kotlinx.serialization.Serializable

/** Type-safe routes for Compose Navigation. Child and parent graphs never link to each other except via the PIN. */
object Routes {
    // child
    @Serializable object Welcome
    @Serializable object WorldMap
    @Serializable data class LessonIntro(val skillId: String)
    @Serializable data class Practice(val setId: String)
    @Serializable object StickerBook
    @Serializable object TreasureChest

    // parent
    @Serializable object ParentPin
    @Serializable object ParentHome
    @Serializable object Profile
    @Serializable data class AddLesson(val subject: String = "math")
    @Serializable data class TypedTask(val subject: String)
    @Serializable data class Reading(val lessonId: String)
    @Serializable data class ConfirmSkills(val lessonId: String)
    @Serializable object Calendar
    @Serializable object Progress
    @Serializable object Settings
}
