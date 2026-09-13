package quest.core.platform

import androidx.compose.runtime.Composable
import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.module.Module
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Text-to-speech. Rate 0.82, pitch 1.15, en-GB where available; a new utterance cancels the current one. */
interface Speaker {
    fun speak(text: String)
    fun stop()
    fun shutdown() {}
}

object SilentSpeaker : Speaker {
    override fun speak(text: String) {}
    override fun stop() {}
}

expect class DriverFactory {
    fun create(): SqlDriver
}

/** Platform bindings: Speaker, DriverFactory, anything that needs a Context. */
expect fun platformModule(): Module

expect val platformName: String

enum class PickKind { PDF, PPTX, CAMERA, GALLERY }

class PickedFile(val name: String, val mimeType: String, val bytes: ByteArray)

interface FilePickerLauncher {
    fun launch(kind: PickKind)
}

@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): FilePickerLauncher

object Ids {
    @OptIn(ExperimentalUuidApi::class)
    fun random(): String = Uuid.random().toString()
}

object Today {
    fun date(): LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault())
    fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
