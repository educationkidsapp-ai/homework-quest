package quest.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import quest.core.db.QuestDatabase
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

