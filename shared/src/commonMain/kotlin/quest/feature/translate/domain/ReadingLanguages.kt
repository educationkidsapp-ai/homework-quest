package quest.feature.translate.domain

import quest.api.dto.ReadingLanguage
import quest.api.dto.TranslationSource

/**
 * Feature 29 — the parent language bridge (`docs/features/29-parent-language-bridge.md`), app side.
 *
 * The owner's rule decides everything here: **the original is the record and is never replaced.** A translation is an
 * addition she asks for, it always carries the "Machine-translated" label, and "Show original" is one tap away.
 *
 * This file is the pure part — which languages exist, how each is spelled in itself, which way it reads, and whether
 * offering Translate on a piece of text makes any sense at all — so all of it is testable without a screen.
 */
object ReadingLanguages {
    /**
     * The eight the pilot school's families read, in the order the picker shows them: the school's own two first,
     * then the rest as the families' numbers run.
     */
    val all: List<ReadingLanguage> = listOf(
        ReadingLanguage.AR, ReadingLanguage.EN, ReadingLanguage.UR, ReadingLanguage.HI,
        ReadingLanguage.BN, ReadingLanguage.FIL, ReadingLanguage.FR, ReadingLanguage.ID,
    )

    /**
     * Each language **in its own name**, which is the only spelling a parent who reads neither Arabic nor English can
     * recognise: a row saying "Urdu" is no use to somebody looking for اردو. The same table in both app languages for
     * that reason — this is not copy to translate.
     */
    fun name(lang: ReadingLanguage): String = when (lang) {
        ReadingLanguage.AR -> "العربية"
        ReadingLanguage.EN -> "English"
        ReadingLanguage.UR -> "اردو"
        ReadingLanguage.HI -> "हिन्दी"
        ReadingLanguage.BN -> "বাংলা"
        ReadingLanguage.FIL -> "Filipino"
        ReadingLanguage.FR -> "Français"
        ReadingLanguage.ID -> "Bahasa Indonesia"
    }

    /** Arabic and Urdu are written right to left; the translated text is laid out that way whatever the app's own language is. */
    fun isRtl(lang: ReadingLanguage): Boolean = lang == ReadingLanguage.AR || lang == ReadingLanguage.UR

    /**
     * Which of the school's two languages a piece of staff-written text is, by script. Staff write in Arabic or
     * English — the only two the dashboard offers — so this is a two-way decision and not language detection: it
     * exists to answer "would translating this change anything?", and a mixed sentence counts as Arabic as soon as a
     * tenth of its letters are, because that tenth is the part she cannot read.
     */
    fun scriptLanguage(text: String): ReadingLanguage {
        val letters = text.count { it.isLetter() }
        if (letters == 0) return ReadingLanguage.EN
        val arabic = text.count { it in '؀'..'ۿ' || it in 'ݐ'..'ݿ' || it in 'ﭐ'..'﷿' }
        return if (arabic * 10 >= letters) ReadingLanguage.AR else ReadingLanguage.EN
    }

    /**
     * Whether a reader is offered Translate at all. Three things have to hold, and each one on its own is a reason to
     * draw nothing: the school bought the bridge, she has chosen a reading language, and the text is not already in
     * it. The last is what keeps a Translate button off an Arabic message for a parent who reads Arabic.
     */
    fun offered(flagOn: Boolean, reading: ReadingLanguage?, source: ReadingLanguage): Boolean =
        flagOn && reading != null && reading != source
}

/** One translatable item: a source row and the language asked for. The cache's key, so Urdu and Hindi never share one. */
data class TranslateKey(val kind: TranslationSource, val id: String, val lang: ReadingLanguage)

/**
 * Why a translation did not arrive, in the words a parent is owed. The server's codes collapse to these four: there
 * is nothing she can do about a provider that failed, but "you have asked for a lot in one hour" and "the connection
 * is gone" are two different waits.
 */
enum class TranslateFailure { RATE_LIMITED, REPHRASE, MODEL_FAILED, OFFLINE }

/**
 * What one item's Translate action is doing. [text] survives [showing] going false — "Show original" hides the
 * translation, it does not throw it away, so showing it again costs nothing and asks nothing of the server.
 */
data class ItemTranslation(
    val loading: Boolean = false,
    val text: String? = null,
    val showing: Boolean = false,
    val failure: TranslateFailure? = null,
)
