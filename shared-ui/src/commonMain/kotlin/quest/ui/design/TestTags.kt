package quest.ui.design

/**
 * Stable identifiers for UI tests. On iOS a `Modifier.testTag` is the element's `accessibilityIdentifier`, so the
 * XCUITest finds the app's own controls by these and not by their wording — copy, language and emoji can change
 * without breaking it. Lesson *content* (a word, a picture's name) is still found by its label: that is seed data.
 */
object TestTags {
    const val SIGN_IN_EMAIL = "signin.email"
    const val SIGN_IN_PASSWORD = "signin.password"
    const val SIGN_IN_SUBMIT = "signin.submit"
    fun childRow(childId: String) = "children.child.$childId"
    const val HOME_PARENT_PORTAL = "home.parentPortal"
    fun homeLesson(lessonId: String) = "home.lesson.$lessonId"
    const val LESSON_CTA = "lesson.cta"
    const val STOP_DONE = "stop.done"
    const val STOP_CHECK = "stop.check"
    const val STOP_NEXT = "stop.next"
    /** The two halves of one pair of a match stop share the pair's id, so a test can pair them without reading the layout. */
    fun matchLeft(pairId: String) = "stop.match.left.$pairId"
    fun matchRight(pairId: String) = "stop.match.right.$pairId"
    const val LESSON_COMPLETE = "lesson.complete"
    const val LESSON_CERTIFICATE = "lesson.certificate"
    const val LESSON_BACK_HOME = "lesson.backHome"
    fun pinKey(key: String) = "pin.key.$key"
    const val PARENT_HOME = "parent.home"
    fun parentLesson(lessonId: String) = "parent.lesson.$lessonId"
}
