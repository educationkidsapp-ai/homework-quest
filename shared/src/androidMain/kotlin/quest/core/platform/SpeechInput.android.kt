package quest.core.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * Feature 22 on Android: the platform `SpeechRecognizer` in this process, asked for `ar-SA` with partial results on.
 *
 * `RECORD_AUDIO` is requested through the same launcher pattern `Media.android.kt` uses for the child's retelling, so
 * there is one way the app asks for the microphone.
 */
@Composable
actual fun rememberSpeechInput(): SpeechInput {
    val context = LocalContext.current
    val input = remember(context) { AndroidSpeechInput(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        input.permissionAnswered(granted)
    }
    SideEffect { input.askPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) } }
    DisposableEffect(input) { onDispose { input.release() } }
    return input
}

/**
 * **Everything here runs on the main thread**, which is where `SpeechRecognizer` must be created and driven: the
 * screen starts and stops it from the composition, and every restart happens inside a listener callback.
 *
 * The recogniser hands over one utterance and stops, so [listen] is called again from `onResults` and from the two
 * errors that mean nothing more than silence — the employee's two minutes are one recording to her, however many
 * utterances the platform splits them into. The cap itself is the view model's; this keeps going until [stop].
 */
private class AndroidSpeechInput(private val context: Context) : SpeechInput {

    private var recognizer: SpeechRecognizer? = null
    private var onText: ((String, Boolean) -> Unit)? = null
    private var onEnd: (() -> Unit)? = null

    /** True between [start] and the end of recording, which is what tells a callback to listen again. */
    private var recording = false
    private var ended = false

    /** What the recogniser has already committed; the live partial is appended to it for display. */
    private val committed = StringBuilder()

    /** Set by the composition, because only a composable can own an activity-result launcher. */
    var askPermission: (() -> Unit)? = null

    override val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(onText: (String, Boolean) -> Unit, onEnd: () -> Unit): Boolean {
        if (!available) return false
        stop()
        this.onText = onText
        this.onEnd = onEnd
        committed.clear()
        recording = true
        ended = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listen()
            return true
        }
        val ask = askPermission
        if (ask == null) {
            finish()
            return false
        }
        ask()
        return true
    }

    /** The launcher's answer. A refusal ends the recording with nothing said, which the screen reports as such. */
    fun permissionAnswered(granted: Boolean) {
        if (!recording) return
        if (granted) listen() else finish()
    }

    override fun stop() {
        if (!recording) {
            release()
            return
        }
        recording = false
        runCatching { recognizer?.stopListening() }
        release()
        finish()
    }

    fun release() {
        recognizer?.let { r -> runCatching { r.cancel() }; runCatching { r.destroy() } }
        recognizer = null
    }

    private fun finish() {
        if (ended) return
        ended = true
        recording = false
        val end = onEnd
        onText = null
        onEnd = null
        end?.invoke()
    }

    private fun listen() {
        if (!recording) return
        val r = recognizer ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (r == null) {
            finish()
            return
        }
        recognizer = r
        r.setRecognitionListener(listener)
        runCatching { r.startListening(request()) }.onFailure { finish() }
    }

    private fun request(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, SPEECH_LOCALE)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
    }

    /** The whole transcript so far: what is committed, plus whatever is being said right now. */
    private fun emit(live: String, final: Boolean) {
        val whole = (committed.toString() + " " + live).trim().replace(WHITESPACE, " ")
        onText?.invoke(whole, final)
    }

    private fun best(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            emit(best(partialResults), final = false)
        }

        override fun onResults(results: Bundle?) {
            val said = best(results)
            if (said.isNotEmpty()) {
                if (committed.isNotEmpty()) committed.append(' ')
                committed.append(said)
            }
            emit("", final = true)
            // One utterance is not the end of her two minutes: listen again until the view model says stop.
            listen()
        }

        override fun onError(error: Int) {
            if (!recording) return
            // Silence is not a failure: she paused. Anything else (no network for the engine, the microphone taken
            // away, the permission revoked mid-recording) ends it rather than spinning on a recogniser that is gone.
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (silence) listen() else finish()
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
