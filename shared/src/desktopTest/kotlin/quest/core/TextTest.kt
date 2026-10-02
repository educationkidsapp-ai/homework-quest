package quest.core

import kotlinx.datetime.LocalDate
import quest.core.text.isolate
import quest.core.text.longDate
import quest.feature.parent.presentation.Strings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TextTest {
    /** M4 (D12): typed text carries its own direction — first-strong isolate, the marks invisible. */
    @Test fun typedTextIsWrappedInAFirstStrongIsolate() {
        val comment = "Good effort, Hala. Practise counting on from 2 and 4."
        assertEquals("⁨$comment⁩", isolate(comment))
        assertEquals("", isolate(""), "nothing to isolate, nothing added")
        assertEquals(comment, isolate(comment).trim('⁨', '⁩'))
    }

    /** M4 (D10): "3 October", never "3/10" beside a score. */
    @Test fun aDateIsDayAndMonthName_inTheReadersLanguage() {
        val date = LocalDate(2026, 10, 3)
        assertEquals("3 October", longDate(date, Strings.en.months, currentYear = 2026))
        assertEquals("3 أكتوبر", longDate(date, Strings.ar.months, currentYear = 2026))
        assertEquals("3 October 2025", longDate(LocalDate(2025, 10, 3), Strings.en.months, currentYear = 2026))
        assertFalse(longDate(date, Strings.en.months, 2026).contains("/"))
    }
}
