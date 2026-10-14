package quest.feature.staff

import quest.feature.staff.presentation.StaffStrings
import quest.feature.staff.presentation.afterHoursText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Feature 19: the morning summary row reads in her language, from the `count` on its link — and in Arabic in all
 * four of its forms, because "وصلتك 2 رسائل" is not a sentence anybody writes.
 */
class StaffChatTextTest {
    private fun summary(count: Int, s: StaffStrings) =
        afterHoursText("chat.after_hours_summary", "/staff/chat/threads?count=$count", s)?.second

    @Test fun `Arabic counts in one, two, few and many`() {
        assertEquals("وصلتك رسالة واحدة خارج الدوام", summary(1, StaffStrings.ar))
        assertEquals("وصلتك رسالتان خارج الدوام", summary(2, StaffStrings.ar))
        assertEquals("وصلتك 3 رسائل خارج الدوام", summary(3, StaffStrings.ar))
        assertEquals("وصلتك 10 رسائل خارج الدوام", summary(10, StaffStrings.ar))
        assertEquals("وصلتك 11 رسالة خارج الدوام", summary(11, StaffStrings.ar))
    }

    @Test fun `English says it one way, with the singular spelled out`() {
        assertEquals("You got 1 message after hours", summary(1, StaffStrings.en))
        assertEquals("You got 4 messages after hours", summary(4, StaffStrings.en))
    }

    @Test fun `the row is titled Messages in her language`() {
        assertEquals(StaffStrings.ar.messages, afterHoursText("chat.after_hours_summary", "/staff/chat/threads?count=2", StaffStrings.ar)?.first)
    }

    @Test fun `any other row, and a summary with no count, keep the server's words`() {
        assertNull(afterHoursText("chat.message", "/teacher/chat?thread=t", StaffStrings.ar))
        assertNull(afterHoursText("chat.after_hours_summary", "/staff/chat/threads", StaffStrings.ar))
        assertNull(afterHoursText("chat.after_hours_summary", "/staff/chat/threads?count=0", StaffStrings.ar))
    }
}
