package quest.ui.stops

import quest.api.dto.NumberLine

/** What a stop composable reports to the player (app) or the preview (admin). */
sealed interface StopEvent {
    /** Single-answer stop answered correctly on the given attempt (1 = first try). */
    data class Correct(val attempt: Int, val answer: String) : StopEvent
    /** Single-answer stop answered wrongly: the player shows the hint sheet; the tile is already dimmed. */
    data class Wrong(val attempt: Int, val hint: String, val numberLine: NumberLine?) : StopEvent
    /** Info / multi / open / exit stop finished with the stars it earned. */
    data class Completed(val stars: Int, val answer: String = "", val mistakes: Int = 0) : StopEvent
    data class Speak(val text: String) : StopEvent
}
