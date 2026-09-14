package quest.core.platform

import androidx.compose.runtime.Composable
import quest.ui.stops.StopMedia

/** Microphone recording + playback for retell / openAnswer (Android MediaRecorder, iOS AVAudioRecorder, desktop none). */
@Composable
expect fun rememberStopMedia(): StopMedia

/** Per-child media files (recordings, drawings) in the app's private storage. */
expect object MediaFiles {
    fun save(name: String, bytes: ByteArray): String
    fun read(path: String): ByteArray?
    fun delete(path: String)
}
