package quest.core.platform

import androidx.compose.runtime.Composable

/**
 * Feature 22: **speech to text on the device**, never on our server.
 *
 * The owner's decision is that no audio file is ever created, uploaded or stored — the recogniser the platform already
 * ships turns speech into text in the app's own process, and only that text leaves the phone. Android uses
 * `SpeechRecognizer`, iOS `SFSpeechRecognizer` with `AVAudioEngine`, and both are asked for [SPEECH_LOCALE].
 *
 * **[onText] is handed the whole transcript so far**, not a delta: Android commits one utterance at a time and iOS
 * grows one string, so the only shape both can promise is "everything heard up to now". `final` says the recogniser
 * has committed what it just handed over and will not revise it; a caller that simply stores the text can ignore it.
 *
 * Nothing here enforces a length — the two-minute cap is the view model's, which calls [stop]. [onEnd] fires once per
 * [start], when recording stopped for a reason the caller did not ask for (the microphone was refused, the recogniser
 * went away) as well as after [stop], so a screen always learns that the microphone is closed.
 */
interface SpeechInput {
    /** False where the platform has no recogniser at all: the Record button is then not offered. */
    val available: Boolean

    /**
     * Starts listening. False when there is nothing to listen with; true when the recogniser is running **or** the
     * microphone permission is being asked for, in which case a refusal arrives as [onEnd] with no text.
     */
    fun start(onText: (text: String, final: Boolean) -> Unit, onEnd: () -> Unit): Boolean

    /** Closes the microphone. Safe to call when nothing is running. */
    fun stop()
}

/** Arabic as it is spoken here — the dialect the spec's leader records in. */
const val SPEECH_LOCALE: String = "ar-SA"

/** A platform with no recogniser. [available] is false, so the feature's entry point is never drawn. */
object NoSpeechInput : SpeechInput {
    override val available: Boolean get() = false
    override fun start(onText: (text: String, final: Boolean) -> Unit, onEnd: () -> Unit): Boolean {
        onEnd()
        return false
    }
    override fun stop() = Unit
}

/** The platform's recogniser, bound to the composition so it is released with the screen that opened it. */
@Composable
expect fun rememberSpeechInput(): SpeechInput
