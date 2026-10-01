package quest.core.platform

import quest.api.dto.Subject

/** The voices read-aloud can ask the platform for. [tag] is a BCP-47 language tag. */
enum class SpeechLanguage(val tag: String) {
    ENGLISH("en-GB"),
    ARABIC("ar-SA"),
    FRENCH("fr-FR"),
}

/**
 * Which voice reads an utterance. The lesson DTO carries a subject and no language, so the subject decides — and the
 * text itself overrules it, one utterance at a time:
 *
 *  1. text written mostly in Arabic script is read by the Arabic voice, whatever the subject (a maths lesson taught in
 *     Arabic, an Arabic button label inside an English lesson);
 *  2. a French lesson is read by the French voice;
 *  3. an Arabic or Islamic-studies lesson is read by the Arabic voice when the text has no letters to judge by
 *     (digits, punctuation); Latin-script text in such a lesson stays with the English voice, which can pronounce it;
 *  4. everything else is English.
 */
object SpeechLanguages {
    fun of(subject: Subject?, text: String): SpeechLanguage {
        val letters = text.filter { it.isLetter() }
        val arabic = letters.count { isArabicScript(it) }
        return when {
            letters.isNotEmpty() && arabic * 2 >= letters.length -> SpeechLanguage.ARABIC
            subject == Subject.FRENCH -> SpeechLanguage.FRENCH
            letters.isEmpty() && (subject == Subject.ARABIC || subject == Subject.RELIGION) -> SpeechLanguage.ARABIC
            else -> SpeechLanguage.ENGLISH
        }
    }

    /** Arabic, Arabic Supplement, Arabic Extended-A and the two presentation-form blocks. */
    private fun isArabicScript(c: Char): Boolean =
        c in '؀'..'ۿ' || c in 'ݐ'..'ݿ' || c in 'ࢠ'..'ࣿ' || c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻿'
}
