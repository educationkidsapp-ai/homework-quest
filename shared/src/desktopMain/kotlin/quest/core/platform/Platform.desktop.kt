package quest.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import quest.core.db.QuestDatabase
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

actual class DriverFactory(private val path: String? = null) {
    actual fun create(): SqlDriver {
        val url = if (path == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$path"
        val driver = JdbcSqliteDriver(url)
        if (path == null || !File(path).exists() || File(path).length() == 0L) QuestDatabase.Schema.create(driver)
        return driver
    }
}

actual val platformName: String = "desktop"

/** Desktop has no TTS in v1: it logs the utterance so the read-aloud path is still exercised. */
class LoggingSpeaker : Speaker {
    override fun speak(text: String) { println("[speak] $text") }
    override fun stop() {}
}

actual fun platformModule(): Module = module {
    single { DriverFactory(File(System.getProperty("user.home"), ".homework-quest/quest.db").also { it.parentFile.mkdirs() }.path) }
    single<Speaker> { LoggingSpeaker() }
}

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): FilePickerLauncher {
    val callback = rememberUpdatedState(onPicked)
    return remember {
        object : FilePickerLauncher {
            override fun launch(kind: PickKind) {
                val dialog = FileDialog(null as Frame?, "Choose slides", FileDialog.LOAD)
                dialog.isMultipleMode = kind == PickKind.GALLERY
                dialog.isVisible = true
                val files = dialog.files.orEmpty().map { f ->
                    val mime = when (f.extension.lowercase()) {
                        "pdf" -> "application/pdf"
                        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                        "png" -> "image/png"
                        else -> "image/jpeg"
                    }
                    PickedFile(f.name, mime, f.readBytes())
                }
                callback.value(files)
            }
        }
    }
}
