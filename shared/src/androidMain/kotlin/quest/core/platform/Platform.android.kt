package quest.core.platform

import quest.feature.today.domain.ExamSittingPresenter
import quest.feature.notifications.domain.ParentBadges
import quest.feature.parent.domain.ParentRepository
import quest.feature.push.domain.PushTokens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import quest.feature.today.domain.TodaySnapshotStore
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
    private var pending: Pair<String, SpeechLanguage>? = null
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            tts.setSpeechRate(0.82f)
            tts.setPitch(1.15f)
            pending?.let { (text, language) -> speak(text, language) }
            pending = null
        }
    }

    /**
     * Points the engine at [language]: the regional voice, then any voice of that language, then English. An engine
     * without Arabic or French data answers `LANG_MISSING_DATA` / `LANG_NOT_SUPPORTED` (and some throw), so every
     * step is checked and the utterance is still spoken — in English — rather than dropped or crashing.
     */
    private fun useVoice(language: SpeechLanguage) {
        val wanted = Locale.forLanguageTag(language.tag)
        val candidates = listOf(wanted, Locale.forLanguageTag(wanted.language), Locale.UK, Locale.ENGLISH)
        candidates.firstOrNull { locale -> runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }
    }

    override fun speak(text: String, language: SpeechLanguage) {
        if (!ready) { pending = text to language; return }
        tts.stop()
        useVoice(language)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "quest-${text.hashCode()}")
    }

    override fun stop() { if (ready) tts.stop() }
    override fun shutdown() { tts.shutdown() }
}

actual fun platformModule(): Module = module {
    single { DriverFactory(androidContext().also { initMediaFiles(it) }) }
    single<Speaker> { AndroidSpeaker(androidContext()) }
    single<TodaySnapshotStore> { AndroidTodaySnapshotStore(androidContext()) }
    single<ExamSittingPresenter> { AndroidExamSittingPresenter(androidContext(), get()) }
    // M5: push for parents over FCM (off where the app has no google-services.json).
    single<PushTokens> { FcmPushTokens(androidContext()) }
    single {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AndroidPushNotifier(androidContext(), language = { get<ParentRepository>().language.value }, onForeground = { scope.launch { get<ParentBadges>().refresh() } })
    }
}
