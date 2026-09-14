package quest.ui.stops

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Recording hooks for the retell / openAnswer stops. The app provides a platform implementation
 * (Android MediaRecorder, iOS AVAudioRecorder); the admin preview and tests use [NoStopMedia].
 */
interface StopMedia {
    val canRecord: Boolean
    /** Asks for the microphone permission if needed and starts recording; false if refused/unavailable. */
    suspend fun startRecording(): Boolean
    /** Stops and returns the audio bytes (AAC/M4A) or null. */
    suspend fun stopRecording(): ByteArray?
    suspend fun play(bytes: ByteArray)
    fun stopPlayback()
}

object NoStopMedia : StopMedia {
    override val canRecord = false
    override suspend fun startRecording() = false
    override suspend fun stopRecording(): ByteArray? = null
    override suspend fun play(bytes: ByteArray) {}
    override fun stopPlayback() {}
}

val LocalStopMedia = staticCompositionLocalOf<StopMedia> { NoStopMedia }
