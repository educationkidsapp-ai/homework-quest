package quest.core.platform

import app.cash.sqldelight.db.SqlDriver
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.koin.core.module.Module
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Text-to-speech. Rate 0.82, pitch 1.15; a new utterance cancels the current one. [language] picks the voice — see
 * [SpeechLanguages] — and a platform that has no such voice installed falls back to its English one rather than fail.
 */
interface Speaker {
    fun speak(text: String, language: SpeechLanguage = SpeechLanguages.of(null, text))
    fun stop()
    fun shutdown() {}
}

object SilentSpeaker : Speaker {
    override fun speak(text: String, language: SpeechLanguage) {}
    override fun stop() {}
}

expect class DriverFactory {
    fun create(): SqlDriver
}

/** Platform bindings: Speaker, DriverFactory, anything that needs a Context. */
expect fun platformModule(): Module

expect val platformName: String


object Ids {
    @OptIn(ExperimentalUuidApi::class)
    fun random(): String = Uuid.random().toString()
}

object Today {
    /**
     * A fixed day for UI-test mode only (`QUEST_UI_TEST`): the fake server's seed lessons have fixed dates, and the child
     * home only asks for the last 30 days, so a wall-clock day drifts them out of the home. Never set outside tests.
     */
    var pinned: LocalDate? = null
    fun date(): LocalDate = pinned ?: Clock.System.todayIn(TimeZone.currentSystemDefault())
    fun epochMillis(): Long = Clock.System.now().toEpochMilliseconds()
}
