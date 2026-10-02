package quest.core.platform

import androidx.compose.runtime.Composable
import quest.ui.stops.NoStopMedia
import quest.ui.stops.StopMedia
import java.io.File

@Composable
actual fun rememberStopMedia(): StopMedia = NoStopMedia

actual object MediaFiles {
    private val dir = File(System.getProperty("user.home"), ".homework-quest/media").apply { mkdirs() }
    actual fun save(name: String, bytes: ByteArray): String = File(dir, name).also { it.writeBytes(bytes) }.absolutePath
    actual fun read(path: String): ByteArray? = File(path).takeIf { it.exists() }?.readBytes()
    actual fun delete(path: String) { File(path).delete() }
    actual fun pathOf(name: String): String = File(dir, name).absolutePath
}

actual object DocumentViewer {
    actual fun directory(): String = File(System.getProperty("java.io.tmpdir"), "homework-quest-documents").apply { mkdirs() }.absolutePath

    actual fun open(name: String, mimeType: String): Boolean = runCatching {
        val file = File(directory(), safeDocumentPath(name))
        if (!file.isFile) return false
        java.awt.Desktop.getDesktop().open(file)
    }.isSuccess
}
