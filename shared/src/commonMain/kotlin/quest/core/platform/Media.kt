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

    /**
     * MH3: the path a [save] of [name] writes to, without writing anything. A cached download has to be found again
     * after a restart, and only the platform knows where its private directory is.
     */
    fun pathOf(name: String): String
}
