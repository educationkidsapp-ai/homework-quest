package quest.feature.staff

import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatSender
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.staff.StaffMe
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.chat.data.ChatSocketClient
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.data.StaffAuth
import quest.feature.staff.data.StaffConversationSource
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffRole
import quest.feature.staff.domain.StaffThreadKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 13: the staff end of a conversation, and above all **which way round it is**.
 *
 * "Mine" on this side is her own user id and not a `sender` word, because a staff thread has a staff member at both
 * ends and both are `teacher` on the wire. A `read` frame is only hers when it says `parent`, because the server
 * sends `read` to everybody on the thread — her own sessions included. And a teacher's `typing` on a parent thread
 * names the **child**, which is the only form `ChatSocketHandler` accepts from her.
 */
class StaffConversationSourceTest {

    private val me = "u-noor"

    private class Gateway : StaffGateway {
        val sent = mutableListOf<Pair<String, SendChatMessageRequest>>()
        val reads = mutableListOf<String>()
        var history: List<ChatMessage> = emptyList()
        var threads: List<ChatThread> = emptyList()

        override suspend fun staffMe(): StaffMe = StaffMe("u-noor", "Noor", "TEACHER", null, "s-1", null, emptyMap())
        override suspend fun staffThreads(): List<ChatThread> = threads
        override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> {
            if (since == null) return history
            val i = history.indexOfFirst { it.id == since }
            return if (i < 0) emptyList() else history.drop(i + 1)
        }
        override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage {
            sent += threadId to request
            return ChatMessage("m-${sent.size}", threadId, ChatSender.TEACHER, "u-noor", request.body, 1_758_450_000_000L)
        }
        override suspend fun staffRead(threadId: String): ChatReadReceipt {
            reads += threadId
            return ChatReadReceipt(threadId, ChatSender.TEACHER, 1_758_450_000_000L)
        }
        override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef {
            onProgress(1f)
            return AttachmentRef(id = "a-1", name = file.name, type = file.contentType, sizeBytes = file.size)
        }
        override suspend fun notifications(limit: Int): List<NotificationView> = emptyList()
        override suspend fun unreadNotificationCount(): UnreadCount = UnreadCount(0)
        override suspend fun markNotificationRead(id: String): NotificationView = error("not used")
        override suspend fun markAllNotificationsRead(): UnreadCount = UnreadCount(0)
        override suspend fun registerDevice(request: RegisterDeviceRequest) = Unit
        override suspend fun unregisterDevice(token: String) = Unit
    }

    /** A socket that records commands instead of opening one; `sendCommand` answers false with no live session. */
    private fun socket(): ChatSocketClient {
        val settings = SettingsStore(Db(DriverFactory(null)))
        return ChatSocketClient("http://localhost:8080", StaffAuth("http://localhost:8080", settings, HttpClient()), HttpClient())
    }

    private fun parentThread() = ChatThread(
        id = "th-parent", childId = "c-1", childName = "Lina Hassan", teacherId = me, teacherName = "Noor",
        className = "3A", subject = "math", unread = 2, staffRole = ChatStaffRole.TEACHER,
        parentName = "Hassan Ali", peerRole = ChatPeerRole.PARENT,
    )

    private fun staffThread() = ChatThread(
        id = "th-staff", childId = "", childName = "", teacherId = "u-adel", teacherName = "Mr Adel",
        staffRole = ChatStaffRole.MANAGERIAL, peerRole = ChatPeerRole.MANAGERIAL,
    )

    @Test fun `a parent thread names the parent, the child and her own typing form`() {
        val key = StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER)
        assertEquals("th-parent", key.threadId)
        assertEquals("Hassan Ali", key.peerName, "the facade names her in teacherName; the header wants the parent")
        assertEquals("Lina Hassan", key.childName)
        assertEquals("c-1", key.childId)
        assertTrue(key.withParent)
        assertTrue(key.typeByChild, "a teacher's typing command on a parent thread is keyed by child")
        assertEquals("", key.peerUserId, "a parent has no user id on the row, so no presence is expected")
    }

    /** A coordinator, a manager or an Admin names every thread by id — the child form is not hers to send. */
    @Test fun `a non-teacher types by thread even on a parent thread`() {
        val key = StaffThreadKey.of(parentThread(), me, StaffRole.COORDINATOR)
        assertFalse(key.typeByChild)
    }

    @Test fun `a staff thread names the colleague and her role`() {
        val key = StaffThreadKey.of(staffThread(), me, StaffRole.TEACHER)
        assertEquals("Mr Adel", key.peerName)
        assertEquals("u-adel", key.peerUserId)
        assertFalse(key.withParent)
        assertFalse(key.typeByChild)
        assertNull(key.childId)
        assertEquals(ChatPeerRole.MANAGERIAL, key.peerRole)
    }

    @Test fun `her own messages are hers and the parent's are not`() {
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), Gateway(), socket())
        val mine = ChatMessage("m-1", "th-parent", ChatSender.TEACHER, me, "Good morning", 1L)
        val hers = ChatMessage("m-2", "th-parent", ChatSender.PARENT, "p-1", "Thank you", 2L)
        assertTrue(source.isMine(mine))
        assertFalse(source.isMine(hers))
        assertFalse(source.isPeerMessage(mine))
        assertTrue(source.isPeerMessage(hers))
    }

    /**
     * The one case a `sender` word cannot answer: a staff thread, where both ends are `teacher`. Only the user id
     * tells them apart, which is why the source asks that and not the word.
     */
    @Test fun `on a staff thread the colleague's message is not hers`() {
        val source = StaffConversationSource(StaffThreadKey.of(staffThread(), me, StaffRole.TEACHER), Gateway(), socket())
        val mine = ChatMessage("m-1", "th-staff", ChatSender.TEACHER, me, "Sending it now", 1L)
        val colleague = ChatMessage("m-2", "th-staff", ChatSender.TEACHER, "u-adel", "Please send the plan", 2L)
        assertTrue(source.isMine(mine))
        assertFalse(source.isMine(colleague))
        assertTrue(source.isPeerMessage(colleague))
    }

    @Test fun `only the parent's read ticks her messages`() {
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), Gateway(), socket())
        assertTrue(source.isPeerRead(ChatSender.PARENT))
        assertFalse(source.isPeerRead(ChatSender.TEACHER), "the server echoes her own read back to her sessions")
    }

    /** A `typing` frame never reaches the person who sent it, so one on this thread is always the other side's. */
    @Test fun `any typing frame on the thread is the other side's`() {
        val source = StaffConversationSource(StaffThreadKey.of(staffThread(), me, StaffRole.TEACHER), Gateway(), socket())
        assertTrue(source.isPeerTyping(ChatSender.TEACHER))
        assertTrue(source.isPeerTyping(ChatSender.PARENT))
    }

    @Test fun `presence is only about the person on the other end`() {
        val source = StaffConversationSource(StaffThreadKey.of(staffThread(), me, StaffRole.TEACHER), Gateway(), socket())
        assertTrue(source.isPeer("u-adel"))
        assertFalse(source.isPeer("u-someone-else"))
    }

    @Test fun `sending and reading go to her own thread by id`() = runTest {
        val gateway = Gateway()
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), gateway, socket())
        val confirmed = source.send("Homework is on page 12", "client-1", listOf("a-1"))
        assertEquals("th-parent", confirmed.threadId)
        assertEquals(1, gateway.sent.size)
        assertEquals("th-parent", gateway.sent[0].first)
        assertEquals("Homework is on page 12", gateway.sent[0].second.body)
        assertEquals(listOf("a-1"), gateway.sent[0].second.attachmentIds)
        assertEquals("client-1", gateway.sent[0].second.clientId)
        source.markRead()
        assertEquals(listOf("th-parent"), gateway.reads)
    }

    /** Her threads always arrive with an id, so the conversation never has to look one up. */
    @Test fun `the thread id is known from the start`() = runTest {
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), Gateway(), socket())
        assertEquals("th-parent", source.resolveThreadId())
    }

    @Test fun `paging asks the facade for what came after the last message`() = runTest {
        val gateway = Gateway()
        gateway.history = listOf(
            ChatMessage("m-1", "th-parent", ChatSender.PARENT, "p-1", "one", 1L),
            ChatMessage("m-2", "th-parent", ChatSender.TEACHER, me, "two", 2L),
        )
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), gateway, socket())
        assertEquals(2, source.messages().size)
        assertEquals(listOf("m-2"), source.messages(since = "m-1").map { it.id })
    }

    @Test fun `an upload goes through the staff media route`() = runTest {
        val source = StaffConversationSource(StaffThreadKey.of(parentThread(), me, StaffRole.TEACHER), Gateway(), socket())
        var progress = 0f
        val ref = source.upload(StagedUpload("/tmp/page.pdf", "page.pdf", "application/pdf", 2_048)) { progress = it }
        assertEquals("page.pdf", ref.name)
        assertEquals("application/pdf", ref.type)
        assertEquals(1f, progress)
    }
}
