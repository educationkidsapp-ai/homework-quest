package quest.api

import quest.api.dto.AttendanceLink
import quest.api.dto.NotificationKind
import quest.api.dto.PushMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** B4: the FCM data map the server sends is the one the app reads back, key for key. */
class PushMessageTest {
    @Test fun aChatPushRoundTripsAndLeavesAbsentValuesOut() {
        val push = PushMessage(NotificationKind.CHAT_MESSAGE, "Message from Ms Sara", "Bring a ruler.", notificationId = "n1",
            childId = "c1", link = "/children/c1/chat/t1", collapseKey = "chat:th1")
        val data = push.toData()
        assertEquals("chat.message", data[PushMessage.KIND])
        assertFalse(PushMessage.BROADCAST_ID in data)
        assertEquals(push, PushMessage.fromData(data))
    }

    @Test fun anExamPushCarriesItsWindow() {
        val push = PushMessage(NotificationKind.EXAM_PUBLISHED, "New exam: Maths", notificationId = "n2", childId = "c1",
            link = "/children/c1/map", collapseKey = "lesson:e1", opensAt = 1_700_000_000_000, closesAt = 1_700_003_600_000)
        assertEquals("1700000000000", push.toData()[PushMessage.OPENS_AT])
        assertEquals(push, PushMessage.fromData(push.toData()))
    }

    @Test fun everyKindHasItsWireName() {
        assertEquals("broadcast.posted", PushMessage.kindName(NotificationKind.BROADCAST_POSTED))
        assertEquals("exam.released", PushMessage.kindName(NotificationKind.EXAM_RELEASED))
        assertEquals("complaint.status", PushMessage.kindName(NotificationKind.COMPLAINT_STATUS))
        assertEquals("complaint.new", PushMessage.kindName(NotificationKind.COMPLAINT_NEW))
        assertEquals("complaint.message", PushMessage.kindName(NotificationKind.COMPLAINT_MESSAGE))
        assertEquals("attendance.reminder", PushMessage.kindName(NotificationKind.ATTENDANCE_REMINDER))
        assertEquals("attendance.alert", PushMessage.kindName(NotificationKind.ATTENDANCE_ALERT))
        assertEquals("attendance.digest", PushMessage.kindName(NotificationKind.ATTENDANCE_DIGEST))
    }

    @Test fun anAttendanceLinkReadsBackItsParameters() {
        val link = "/notifications?attendance=late&staff=USER:u-1&name=%D8%A3.%20%D8%AE%D8%A7%D9%84%D8%AF&date=2026-10-18&minutes=18"
        val params = AttendanceLink.params(link)
        assertEquals("late", params[AttendanceLink.TYPE])
        assertEquals("أ. خالد", params[AttendanceLink.NAME])
        assertEquals("18", params[AttendanceLink.MINUTES])
        assertEquals("USER:u-1", params[AttendanceLink.STAFF])
        assertEquals(emptyMap(), AttendanceLink.params("/notifications"))
        assertEquals(emptyMap(), AttendanceLink.params(null))
    }

    @Test fun aComplaintPushNamesTheComplaint() {
        val push = PushMessage(NotificationKind.COMPLAINT_MESSAGE, "Reply from Ms Lina", "We have halved it.", notificationId = "n3",
            childId = "c1", link = "/children/c1/complaints/k1", collapseKey = "complaint:k1", complaintId = "k1")
        assertEquals("k1", push.toData()[PushMessage.COMPLAINT_ID])
        assertEquals(push, PushMessage.fromData(push.toData()))
    }

    @Test fun aMapThatIsNotOursIsNull() {
        assertNull(PushMessage.fromData(mapOf("kind" to "something.else", "title" to "x", "collapseKey" to "k")))
        assertNull(PushMessage.fromData(mapOf("kind" to "exam.released", "collapseKey" to "k")))
    }
}
