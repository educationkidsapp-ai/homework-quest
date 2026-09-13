package quest.core.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import quest.core.db.QuestDatabase
import java.util.Locale

actual class DriverFactory(private val context: Context) {
    actual fun create(): SqlDriver = AndroidSqliteDriver(QuestDatabase.Schema, context, "quest.db")
}

actual val platformName: String = "android"

class AndroidSpeaker(context: Context) : Speaker {
    private var ready = false
    private var pending: String? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            configure()
            pending?.let { speak(it) }
            pending = null
        }
    }

    private fun configure() {
        val gb = Locale.UK
        val result = tts.setLanguage(gb)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts.language = Locale.ENGLISH
        tts.setSpeechRate(0.82f)
        tts.setPitch(1.15f)
    }

    override fun speak(text: String) {
        if (!ready) { pending = text; return }
        tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quest-${text.hashCode()}")
    }

    override fun stop() { if (ready) tts.stop() }
    override fun shutdown() { tts.shutdown() }
}

actual fun platformModule(): Module = module {
    single { DriverFactory(androidContext()) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
}
