package quest.feature.staff

import quest.feature.staff.presentation.StaffStrings
import quest.feature.staff.presentation.attendanceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feature 16: an attendance row of the bell reads in her language, worded from its link, for every type. */
class StaffAttendanceTextTest {
    private val late = "/notifications?attendance=late&staff=USER%3Au-1&name=%D8%A3.+%D8%AE%D8%A7%D9%84%D8%AF&date=2026-10-18&minutes=18"

    @Test fun `an alert names the employee and the minutes in Arabic and English`() {
        assertEquals("الحضور" to "أ. خالد تأخر 18 دقيقة", attendanceText("attendance.alert", late, StaffStrings.ar))
        assertEquals("Attendance" to "أ. خالد is 18 minutes late", attendanceText("attendance.alert", late, StaffStrings.en))
    }

    @Test fun `the reminders read as the spec words them`() {
        fun reminder(type: String) = attendanceText("attendance.reminder", "/notifications?attendance=$type&date=2026-10-18", StaffStrings.ar)?.second
        assertEquals("لم تسجل حضورك بعد", reminder("start_reminder"))
        assertEquals("لا تنس تسجيل الانصراف", reminder("end_reminder"))
        assertEquals("لم يُسجَّل انصرافك اليوم", reminder("missing_checkout_reminder"))
    }

    @Test fun `the digest counts and lists the names`() {
        val link = "/notifications?attendance=digest&date=2026-10-18&late=3&notArrived=1&remote=2&lateNames=A%7CB%7CC&notArrivedNames=D"
        val (title, body) = attendanceText("attendance.digest", link, StaffStrings.ar)!!
        assertEquals("حضور اليوم", title)
        assertEquals("اليوم: 3 متأخرون، 1 لم يصل، 2 عن بعد\nمتأخرون: A، B، C\nلم يصل: D", body)
    }

    @Test fun `every alert type has words in both languages, and other rows keep the server's`() {
        for (s in listOf(StaffStrings.en, StaffStrings.ar))
            for (type in listOf("late", "not_arrived", "early_leave", "missing_checkout"))
                assertTrue(attendanceText("attendance.alert", "/notifications?attendance=$type&name=X&minutes=5", s)!!.second.contains("X"))
        assertNull(attendanceText("chat.message", "/teacher/chat?thread=t", StaffStrings.ar))
    }
}
