package quest.core.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import quest.core.db.QuestDatabase

actual class DriverFactory {
    actual fun create(): SqlDriver = NativeSqliteDriver(QuestDatabase.Schema, "quest.db")
}

actual val platformName: String = "ios"

class IosSpeaker : Speaker {
    private val synthesizer = AVSpeechSynthesizer()
    override fun speak(text: String, language: SpeechLanguage) {
        if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
        // `voiceWithLanguage` answers nil for a language with no voice on the device; English is always present.
        utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(language.tag)
            ?: AVSpeechSynthesisVoice.voiceWithLanguage(SpeechLanguage.ENGLISH.tag)
            ?: AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
        utterance.rate = 0.82f * 0.5f // AVSpeech rate is 0..1 with 0.5 = normal; scale the design's 0.82
        utterance.pitchMultiplier = 1.15f
        synthesizer.speakUtterance(utterance)
    }
    override fun stop() { synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate) }
}

actual fun platformModule(): Module = module {
    single { DriverFactory() }
    single<Speaker> { IosSpeaker() }
}

