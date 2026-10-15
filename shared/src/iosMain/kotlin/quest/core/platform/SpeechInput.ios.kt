@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Feature 22 on iOS: `SFSpeechRecognizer` for `ar-SA` fed by `AVAudioEngine` through an
 * `SFSpeechAudioBufferRecognitionRequest`. The buffers go straight from the microphone into the recogniser and are
 * never written anywhere, which is the owner's "no audio file is ever created" in one sentence.
 *
 * Two permissions are needed and both are asked for here: speech recognition
 * (`NSSpeechRecognitionUsageDescription`) and the microphone (`NSMicrophoneUsageDescription`).
 */
@Composable
actual fun rememberSpeechInput(): SpeechInput {
    val input = remember { IosSpeechInput() }
    DisposableEffect(input) { onDispose { input.stop() } }
    return input
}

private class IosSpeechInput : SpeechInput {

    private val recognizer: SFSpeechRecognizer? = SFSpeechRecognizer(NSLocale(SPEECH_LOCALE))
    private var engine: AVAudioEngine? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    private var onText: ((String, Boolean) -> Unit)? = null
    private var onEnd: (() -> Unit)? = null
    private var recording = false
    private var ended = false

    override val available: Boolean get() = recognizer != null

    override fun start(onText: (text: String, final: Boolean) -> Unit, onEnd: () -> Unit): Boolean {
        val speech = recognizer ?: return false
        stop()
        this.onText = onText
        this.onEnd = onEnd
        recording = true
        ended = false
        // Both authorisations, one after the other, then the engine — all three answers arrive off the main queue, so
        // every step that touches the audio graph is hopped back onto it.
        SFSpeechRecognizer.requestAuthorization { status: SFSpeechRecognizerAuthorizationStatus ->
            onMain {
                if (!recording) return@onMain
                if (status != SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                    finish()
                    return@onMain
                }
                microphone { granted ->
                    if (!recording) return@microphone
                    if (granted) begin(speech) else finish()
                }
            }
        }
        return true
    }

    override fun stop() {
        if (!recording) {
            release()
            return
        }
        recording = false
        request?.endAudio()
        release()
        finish()
    }

    private fun microphone(answer: (Boolean) -> Unit) {
        val session = AVAudioSession.sharedInstance()
        if (session.recordPermission == AVAudioSessionRecordPermissionGranted) answer(true)
        else session.requestRecordPermission { granted -> onMain { answer(granted) } }
    }

    private fun begin(speech: SFSpeechRecognizer) {
        val started = runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryRecord, null)
            session.setActive(true, null)

            val buffered = SFSpeechAudioBufferRecognitionRequest()
            buffered.shouldReportPartialResults = true
            request = buffered

            val audio = AVAudioEngine()
            engine = audio
            val input = audio.inputNode
            input.installTapOnBus(0u, 2048u, input.outputFormatForBus(0u)) { buffer, _ ->
                buffer?.let { buffered.appendAudioPCMBuffer(it) }
            }
            audio.prepare()
            audio.startAndReturnError(null)
        }.getOrDefault(false)

        if (!started) {
            release()
            finish()
            return
        }

        task = speech.recognitionTaskWithRequest(request!!) { result, error ->
            onMain {
                val said = result?.bestTranscription?.formattedString
                // `formattedString` is the whole session so far, which is exactly what `SpeechInput` promises.
                if (said != null) onText?.invoke(said, false)
                if (error != null && recording) {
                    recording = false
                    release()
                    finish()
                }
            }
        }
    }

    private fun release() {
        runCatching { engine?.inputNode?.removeTapOnBus(0u) }
        runCatching { engine?.stop() }
        engine = null
        runCatching { task?.cancel() }
        task = null
        request = null
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
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

    /** The audio graph and the Compose state both belong to the main queue; the callbacks do not arrive on it. */
    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }
}
