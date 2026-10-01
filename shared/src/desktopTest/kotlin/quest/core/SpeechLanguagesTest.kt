package quest.core

import quest.api.dto.Subject
import quest.core.platform.SpeechLanguage
import quest.core.platform.SpeechLanguages
import kotlin.test.Test
import kotlin.test.assertEquals

class SpeechLanguagesTest {
    @Test fun arabicAndReligionLessonsInArabicScriptUseTheArabicVoice() {
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.ARABIC, "اقرأ الجملة ثم اختر الكلمة"))
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.RELIGION, "أركان الإسلام خمسة"))
    }

    @Test fun frenchLessonsUseTheFrenchVoice() {
        assertEquals(SpeechLanguage.FRENCH, SpeechLanguages.of(Subject.FRENCH, "Bonjour, comment ça va ?"))
        assertEquals(SpeechLanguage.FRENCH, SpeechLanguages.of(Subject.FRENCH, "12"))
    }

    @Test fun everythingElseIsEnglish() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguages.of(Subject.MATH, "Count by 2s to 20"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguages.of(Subject.SCIENCE, "Plants need water"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguages.of(null, "Select a lesson to open it."))
    }

    @Test fun arabicScriptOverrulesAnAmbiguousSubject() {
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.MATH, "عدّ بالاثنينات حتى ٢٠"))
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(null, "اختر درساً لفتحه."))
        // an Arabic sentence quoting one Latin word is still Arabic
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.ENGLISH, "اكتب كلمة ship في الفراغ من فضلك"))
    }

    @Test fun textWithNoLettersFollowsTheSubject() {
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.ARABIC, "٣ + ٤"))
        assertEquals(SpeechLanguage.ARABIC, SpeechLanguages.of(Subject.RELIGION, "5"))
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguages.of(Subject.MATH, "3 + 4"))
    }

    @Test fun latinTextInAnIslamicStudiesLessonStaysWithAVoiceThatCanReadIt() {
        assertEquals(SpeechLanguage.ENGLISH, SpeechLanguages.of(Subject.RELIGION, "The five pillars of Islam"))
    }
}
