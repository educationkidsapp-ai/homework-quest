package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Feature 22 on the desktop: a fake, the same parity `FakeContentApi` and `FakeStaffApi` give the rest of the app.
 * The JVM has no Arabic recogniser, and the staff app is a phone app, so the desktop build answers one canned
 * sentence at once rather than pretending to listen — enough to walk the preview and the Send step by hand.
 */
@Composable
actual fun rememberSpeechInput(): SpeechInput = remember { DesktopSpeechInput }

internal object DesktopSpeechInput : SpeechInput {
    override val available: Boolean get() = true

    override fun start(onText: (text: String, final: Boolean) -> Unit, onEnd: () -> Unit): Boolean {
        onText(SAID, true)
        return true
    }

    override fun stop() = Unit

    /** A note in the dialect, as the spec words the use case. */
    private const val SAID = "يا شباب بكرة عندنا رحلة لطلاب الصف الثالث، ودوا معهم وجبة خفيفة وماء."
}
