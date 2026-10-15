package quest.feature.translate.presentation

import quest.feature.translate.domain.TranslateFailure

/**
 * Feature 29's words in English and Arabic — `Strings.translate`. The app's own interface stays in those two
 * languages; only the *content* of a message is translated, so these are not the eight.
 *
 * [machine] is the label the spec requires on every translation, in the reader's own interface language, because it
 * is the app speaking about the text and not part of it.
 */
data class TranslateStrings(
    val translate: String,
    val machine: String,
    val showOriginal: String,
    val translating: String,
    /** The Reading language setting. */
    val readingLanguage: String,
    val readingLanguageHint: String,
    val readingLanguageSaveFailed: String,
    val rateLimited: String,
    val rephrase: String,
    val modelFailed: String,
    val offline: String,
) {
    fun failure(failure: TranslateFailure): String = when (failure) {
        TranslateFailure.RATE_LIMITED -> rateLimited
        TranslateFailure.REPHRASE -> rephrase
        TranslateFailure.MODEL_FAILED -> modelFailed
        TranslateFailure.OFFLINE -> offline
    }

    companion object {
        val en = TranslateStrings(
            translate = "Translate",
            machine = "Machine-translated",
            showOriginal = "Show original",
            translating = "Translating…",
            readingLanguage = "Reading language",
            readingLanguageHint = "The language the school's messages are translated into when you ask. The app itself stays in English or Arabic.",
            readingLanguageSaveFailed = "The language was not saved. Check the connection and try again.",
            rateLimited = "You have asked for a lot of translations this hour. Try again a little later.",
            rephrase = "This one could not be translated. The original is above.",
            modelFailed = "The translation did not work this time. Try again.",
            offline = "No connection, so nothing could be translated. The original is above.",
        )

        val ar = TranslateStrings(
            translate = "ترجمة",
            machine = "ترجمة آلية",
            showOriginal = "إظهار النص الأصلي",
            translating = "جارٍ الترجمة…",
            readingLanguage = "لغة القراءة",
            readingLanguageHint = "اللغة التي تُترجم إليها رسائل المدرسة عند طلبك. أما التطبيق نفسه فيبقى بالعربية أو الإنجليزية.",
            readingLanguageSaveFailed = "لم تُحفظ اللغة. تحقّق من الاتصال وحاول مجددًا.",
            rateLimited = "طلبتِ ترجمات كثيرة في هذه الساعة. حاولي بعد قليل.",
            rephrase = "تعذّرت ترجمة هذه الرسالة. النص الأصلي أعلاه.",
            modelFailed = "لم تنجح الترجمة هذه المرة. حاولي مرة أخرى.",
            offline = "لا يوجد اتصال، فلم تُترجم الرسالة. النص الأصلي أعلاه.",
        )
    }
}
