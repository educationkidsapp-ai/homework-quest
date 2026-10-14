package quest.feature.chat

import quest.api.dto.OfficeHoursStatus
import quest.feature.chat.domain.OfficeHoursWords
import quest.feature.chat.domain.officeHoursBanner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 19: the parent's banner, for every case the spec names — later today, tomorrow, after a weekend, after a
 * holiday, and inside her hours. Every one of them is decided against the **school's** date, which is why there is
 * no clock in this test: Thursday 2026-10-15 is a school date the server sent, not a date this machine has.
 */
class OfficeHoursBannerTest {
    private val sara = "أ. سارة"
    private val saraEn = "Ms Sara"
    private val thursday = "2026-10-15"

    private fun closed(nextStart: String, today: String = thursday, phone: String? = null) =
        OfficeHoursStatus(open = false, nextStart = nextStart, today = today, schoolPhone = phone)

    @Test fun `a next start on the school's own date reads as later today`() {
        assertEquals(
            "أ. سارة خارج ساعات الدوام الآن، وسترد في الدوام القادم (اليوم 1:00 م)",
            officeHoursBanner(sara, closed("2026-10-15T13:00"), OfficeHoursWords.ar)?.outside,
        )
        assertEquals(
            "Ms Sara is outside office hours now and will reply in the next working period (today 1:00 PM)",
            officeHoursBanner(saraEn, closed("2026-10-15T13:00"), OfficeHoursWords.en)?.outside,
        )
    }

    @Test fun `the next day reads as tomorrow`() {
        assertEquals(
            "أ. سارة خارج ساعات الدوام الآن، وسترد في الدوام القادم (غدًا 7:00 ص)",
            officeHoursBanner(sara, closed("2026-10-16T07:00"), OfficeHoursWords.ar)?.outside,
        )
        assertEquals(
            "Ms Sara is outside office hours now and will reply in the next working period (tomorrow 7:00 AM)",
            officeHoursBanner(saraEn, closed("2026-10-16T07:00"), OfficeHoursWords.en)?.outside,
        )
    }

    /** Thursday evening, the Saudi weekend between: the next working day is Sunday, and it is named. */
    @Test fun `after a weekend the weekday is named`() {
        assertEquals(
            "أ. سارة خارج ساعات الدوام الآن، وسترد في الدوام القادم (الأحد 7:00 ص)",
            officeHoursBanner(sara, closed("2026-10-18T07:00"), OfficeHoursWords.ar)?.outside,
        )
        assertEquals(
            "Ms Sara is outside office hours now and will reply in the next working period (Sunday 7:00 AM)",
            officeHoursBanner(saraEn, closed("2026-10-18T07:00"), OfficeHoursWords.en)?.outside,
        )
    }

    /** A holiday on the Sunday pushes the next period to the Monday — the same wording, the right day. */
    @Test fun `after a holiday the weekday is named too`() {
        assertEquals(
            "أ. سارة خارج ساعات الدوام الآن، وسترد في الدوام القادم (الاثنين 7:00 ص)",
            officeHoursBanner(sara, closed("2026-10-19T07:00"), OfficeHoursWords.ar)?.outside,
        )
        assertTrue(
            officeHoursBanner(saraEn, closed("2026-10-19T07:00"), OfficeHoursWords.en)!!.outside.endsWith("(Monday 7:00 AM)"),
        )
    }

    @Test fun `the school's number is a second line, and there is none without it`() {
        val withPhone = officeHoursBanner(sara, closed("2026-10-18T07:00", phone = "+966500000000"), OfficeHoursWords.ar)!!
        // The number keeps its plus on the left in the Arabic app: `ltr()` wraps it in a left-to-right isolate.
        assertEquals("للأمور العاجلة: اتصل بالمدرسة ⁦+966500000000⁩", withPhone.urgent)
        assertEquals(
            "For urgent matters: call the school ⁦+966500000000⁩",
            officeHoursBanner(saraEn, closed("2026-10-18T07:00", phone = "+966500000000"), OfficeHoursWords.en)?.urgent,
        )
        assertNull(officeHoursBanner(sara, closed("2026-10-18T07:00"), OfficeHoursWords.ar)?.urgent)
    }

    @Test fun `there is no banner inside her hours, with no status at all, or with no next start`() {
        assertNull(officeHoursBanner(sara, OfficeHoursStatus(open = true, today = thursday), OfficeHoursWords.ar))
        assertNull(officeHoursBanner(sara, null, OfficeHoursWords.ar))
        assertNull(officeHoursBanner(sara, OfficeHoursStatus(open = false, today = thursday), OfficeHoursWords.ar))
        assertNull(officeHoursBanner(sara, closed("not-a-time"), OfficeHoursWords.ar), "a value this build cannot read says nothing")
    }

    /** Noon and midnight are where a 12-hour clock goes wrong: 12:05 PM, and 12:30 AM rather than 0:30. */
    @Test fun `the clock is twelve-hour at noon and at midnight`() {
        fun time(at: String, words: OfficeHoursWords) =
            officeHoursBanner(saraEn, closed(at, today = "2026-10-15"), words)!!.outside.substringAfterLast('(').trimEnd(')')
        assertEquals("today 12:05 PM", time("2026-10-15T12:05", OfficeHoursWords.en))
        assertEquals("tomorrow 12:30 AM", time("2026-10-16T00:30", OfficeHoursWords.en))
        assertEquals("اليوم 12:05 م", time("2026-10-15T12:05", OfficeHoursWords.ar))
        assertEquals("غدًا 12:30 ص", time("2026-10-16T00:30", OfficeHoursWords.ar))
    }

    /** The staff name is used exactly as the server worded it — no title is added and none is stripped. */
    @Test fun `the name is used as it came`() {
        assertTrue(officeHoursBanner("Mr Adel", closed("2026-10-16T07:00"), OfficeHoursWords.en)!!.outside.startsWith("Mr Adel is outside"))
    }

    @Test fun `the words exist in both languages, and the language picks them`() {
        assertEquals(OfficeHoursWords.ar, OfficeHoursWords.forLanguage("ar"))
        assertEquals(OfficeHoursWords.en, OfficeHoursWords.forLanguage("en"))
        assertEquals(7, OfficeHoursWords.ar.weekdays.size)
        assertEquals("الأحد", OfficeHoursWords.ar.weekdays.last(), "Monday-first, the order isoDayNumber counts in")
    }
}
