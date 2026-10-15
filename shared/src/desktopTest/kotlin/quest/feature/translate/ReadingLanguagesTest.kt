package quest.feature.translate

import quest.api.dto.ReadingLanguage
import quest.feature.translate.domain.ReadingLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Feature 29: the picker and the three conditions that decide whether Translate is offered at all. All pure, so the
 * rule that keeps a Translate button off an Arabic message for a parent who reads Arabic is a unit test.
 */
class ReadingLanguagesTest {
    /** The eight the spec names, each spelled in itself — "Urdu" is no help to somebody looking for اردو. */
    @Test fun thePickerShowsEveryLanguageInItsOwnName() {
        assertEquals(
            listOf(
                ReadingLanguage.AR, ReadingLanguage.EN, ReadingLanguage.UR, ReadingLanguage.HI,
                ReadingLanguage.BN, ReadingLanguage.FIL, ReadingLanguage.FR, ReadingLanguage.ID,
            ),
            ReadingLanguages.all,
        )
        assertEquals(ReadingLanguage.entries.toSet(), ReadingLanguages.all.toSet(), "every language the contract has")
        assertEquals(
            listOf("العربية", "English", "اردو", "हिन्दी", "বাংলা", "Filipino", "Français", "Bahasa Indonesia"),
            ReadingLanguages.all.map { ReadingLanguages.name(it) },
        )
    }

    @Test fun arabicAndUrduReadRightToLeft() {
        assertEquals(
            setOf(ReadingLanguage.AR, ReadingLanguage.UR),
            ReadingLanguages.all.filter { ReadingLanguages.isRtl(it) }.toSet(),
        )
    }

    /** Staff write in the school's two languages, and which one a message is decides whether translating it helps. */
    @Test fun theScriptSaysWhichOfTheSchoolsTwoLanguagesAMessageIs() {
        assertEquals(ReadingLanguage.AR, ReadingLanguages.scriptLanguage("يرجى إرسال وجبة خفيفة غدًا."))
        assertEquals(ReadingLanguage.EN, ReadingLanguages.scriptLanguage("Please send a snack tomorrow."))
        // A sentence with Arabic in it is Arabic: that part is the part she cannot read.
        assertEquals(ReadingLanguage.AR, ReadingLanguages.scriptLanguage("Homework: صفحة 12"))
        assertEquals(ReadingLanguage.EN, ReadingLanguages.scriptLanguage("12:30 👍"), "no letters at all is not Arabic")
    }

    @Test fun nothingIsOfferedWithoutTheFlagALanguageOrADifference() {
        assertFalse(
            ReadingLanguages.offered(flagOn = false, reading = ReadingLanguage.UR, source = ReadingLanguage.EN),
            "the school has not bought the bridge",
        )
        assertFalse(
            ReadingLanguages.offered(flagOn = true, reading = null, source = ReadingLanguage.EN),
            "she has chosen no reading language",
        )
        assertFalse(
            ReadingLanguages.offered(flagOn = true, reading = ReadingLanguage.AR, source = ReadingLanguage.AR),
            "the message is already in the language she reads",
        )
        assertTrue(ReadingLanguages.offered(flagOn = true, reading = ReadingLanguage.UR, source = ReadingLanguage.EN))
    }
}
