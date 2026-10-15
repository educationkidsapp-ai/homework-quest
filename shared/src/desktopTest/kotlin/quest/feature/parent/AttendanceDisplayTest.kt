package quest.feature.parent

import quest.api.dto.ChildAttendanceRecord
import quest.feature.parent.presentation.Strings
import quest.feature.parent.presentation.attendanceDisplay
import quest.ui.design.DashboardPillVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 23: what the parent's Today card and her calendar say about one day. A remote study day reads as itself,
 * and a status nobody marked never reaches her as an error or as a raw wire word.
 */
class AttendanceDisplayTest {
    private fun record(status: String, remote: Boolean = false) =
        ChildAttendanceRecord(date = "2026-10-15", status = status, remoteStudyDay = remote)

    @Test fun `a remote study day says so, in her language, instead of a status chip`() {
        val en = attendanceDisplay(record("NOT_MARKED", remote = true), Strings.en)
        assertEquals("Remote study day", en.headline)
        assertNull(en.chip)
        assertTrue(!en.muted)

        val ar = attendanceDisplay(record("NOT_MARKED", remote = true), Strings.ar)
        assertEquals("يوم دراسة عن بعد", ar.headline)
        assertNull(ar.chip)
    }

    @Test fun `the flag wins over whatever the status says, so a marked remote day still reads as remote`() {
        for (status in listOf("NOT_MARKED", "PRESENT", "ABSENT", ""))
            assertEquals(Strings.en.remoteStudyDay, attendanceDisplay(record(status, remote = true), Strings.en).headline)
    }

    @Test fun `NOT_MARKED on an ordinary day is not news, not an error`() {
        val display = attendanceDisplay(record("NOT_MARKED"), Strings.en)
        assertEquals("Not recorded yet", display.headline)
        assertNull(display.chip)
        assertTrue(display.muted)
        assertEquals(DashboardPillVariant.NEUTRAL, display.variant)
    }

    @Test fun `a status this app does not know is never shown raw`() {
        for (status in listOf("PENDING", "", "half_day"))
            for (s in listOf(Strings.en, Strings.ar)) {
                val display = attendanceDisplay(record(status), s)
                assertEquals(s.noAttendanceRecorded, display.headline)
                assertNull(display.chip)
            }
    }

    @Test fun `no record at all reads as nothing recorded yet`() {
        val display = attendanceDisplay(null, Strings.ar)
        assertEquals("لم يُسجل بعد", display.headline)
        assertNull(display.chip)
        assertTrue(display.muted)
    }

    @Test fun `the four marked statuses keep their words and their chips`() {
        val s = Strings.en
        assertEquals(s.present to DashboardPillVariant.SUCCESS, attendanceDisplay(record("PRESENT"), s).let { it.chip to it.variant })
        assertEquals(s.late to DashboardPillVariant.WARNING, attendanceDisplay(record("late"), s).let { it.chip to it.variant })
        assertEquals(s.absent to DashboardPillVariant.ERROR, attendanceDisplay(record("ABSENT"), s).let { it.chip to it.variant })
        assertEquals(s.excused to DashboardPillVariant.INFO, attendanceDisplay(record("EXCUSED"), s).let { it.chip to it.variant })
        assertTrue(attendanceDisplay(record("PRESENT"), s).headline.endsWith(s.present))
    }
}
