package quest.api

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

    @Test fun everyKindHasItsWireName() {
        assertEquals("broadcast.posted", PushMessage.kindName(NotificationKind.BROADCAST_POSTED))
        assertEquals("exam.released", PushMessage.kindName(NotificationKind.EXAM_RELEASED))
    }

    @Test fun aMapThatIsNotOursIsNull() {
        assertNull(PushMessage.fromData(mapOf("kind" to "something.else", "title" to "x", "collapseKey" to "k")))
        assertNull(PushMessage.fromData(mapOf("kind" to "exam.released", "collapseKey" to "k")))
    }
}
