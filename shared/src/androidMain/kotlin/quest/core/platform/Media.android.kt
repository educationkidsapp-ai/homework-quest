package quest.core.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import quest.ui.stops.StopMedia
import java.io.File

private lateinit var appContext: Context
internal fun initMediaFiles(context: Context) { appContext = context.applicationContext }

actual object MediaFiles {
    private val dir: File get() = File(appContext.filesDir, "media").apply { mkdirs() }
    actual fun save(name: String, bytes: ByteArray): String = File(dir, name).also { it.writeBytes(bytes) }.absolutePath
    actual fun read(path: String): ByteArray? = File(path).takeIf { it.exists() }?.readBytes()
    actual fun delete(path: String) { File(path).delete() }
}

@Composable
actual fun rememberStopMedia(): StopMedia {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> pending[0]?.complete(granted) }
    return remember {
        object : StopMedia {
            private var recorder: MediaRecorder? = null
            private var file: File? = null
            private var player: MediaPlayer? = null
            override val canRecord = true

            private suspend fun permission(): Boolean {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
                val d = CompletableDeferred<Boolean>(); pending[0] = d
                launcher.launch(Manifest.permission.RECORD_AUDIO)
                return d.await()
            }

            override suspend fun startRecording(): Boolean {
                if (!permission()) return false
                return runCatching {
                    val f = File.createTempFile("retell", ".m4a", context.cacheDir); file = f
                    val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
                    r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(64_000); r.setAudioSamplingRate(44_100)
                    r.setOutputFile(f.absolutePath); r.prepare(); r.start(); recorder = r; true
                }.getOrDefault(false)
            }

            override suspend fun stopRecording(): ByteArray? = withContext(Dispatchers.IO) {
                runCatching { recorder?.stop() }; recorder?.release(); recorder = null
                file?.takeIf { it.length() > 0 }?.readBytes()
            }

            override suspend fun play(bytes: ByteArray) {
                stopPlayback()
                val f = File.createTempFile("play", ".m4a", context.cacheDir).also { it.writeBytes(bytes) }
                player = MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { it.release(); f.delete() }; start() }
            }

            override fun stopPlayback() { runCatching { player?.stop(); player?.release() }; player = null }
        }
    }
}
