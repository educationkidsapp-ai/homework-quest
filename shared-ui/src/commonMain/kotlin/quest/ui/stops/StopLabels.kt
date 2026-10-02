package quest.ui.stops

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The words the stop composables draw and speak themselves — buttons, counters, the "try again" sentences. The
 * question, its options and its hint come from the lesson; these are the frame around them. English by default; the
 * app provides the parent's language through [LocalStopLabels], so shared-ui needs no string table of its own.
 *
 * `{n}` is replaced with a count.
 */
data class StopLabels(
    val check: String = "Check",
    val done: String = "Done",
    val continueLabel: String = "Continue",
    val finished: String = "Finished",
    val next: String = "Next",
    val listen: String = "Listen",
    val sayIt: String = "Say it",
    val readToMe: String = "Read to me",
    val reading: String = "Reading…",
    val showAnother: String = "Show another example",
    val clear: String = "Clear",
    val record: String = "Record",
    val stopRecording: String = "Stop",
    val recordAgain: String = "Record again",
    val play: String = "Play",
    val sayAnswer: String = "Say your answer out loud.",
    val trueLabel: String = "True",
    val falseLabel: String = "False",
    val moreToSelect: String = "{n} more to select",
    val notThatOne: String = "Not that one.",
    val someRight: String = "Some are right. Keep going.",
    val notThose: String = "Not those. Try again.",
    val notAMatch: String = "Not a match. Try again.",
    val orderPartlyRight: String = "The first {n} are right. Try the rest again.",
    val readSentenceAgain: String = "Read the sentence again. Which word makes sense?",
    val alreadyTried: String = "already tried",
    // spoken by a screen reader only
    val drawingStrokes: String = "drawing with {n} strokes",
    val drawingPad: String = "drawing pad, {n} strokes",
    val groupOf: String = "group of {n}",
    val colour: String = "colour {n}",
    val selected: String = "selected",
    val numberWord: String = "number {n}",
    val missingNumber: String = "missing number",
)

val LocalStopLabels = staticCompositionLocalOf { StopLabels() }
