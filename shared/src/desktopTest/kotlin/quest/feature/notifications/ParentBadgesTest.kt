package quest.feature.notifications

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatSender
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.feature.children.domain.ChildrenRepository
import quest.feature.notifications.domain.ParentBadges
import quest.feature.notifications.domain.UnreadCounts
import quest.feature.notifications.domain.UnreadSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M4 (D5): the bottom bar's two badges — asked on demand, moved live by the chat socket, kept when offline. */
@OptIn(ExperimentalCoroutinesApi::class)
class ParentBadgesTest {
    private class Children(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class Source : UnreadSource {
        var notifications: Int? = 0
        var messages: Int? = 0
        var asked = 0
        override suspend fun notifications(childId: String): Int? { asked++; return notifications }
        override suspend fun messages(childId: String): Int? = messages
    }

    private val hala = Child("c1", "Hala", "sun", Curriculum.BRITISH, 1)
    private fun message(sender: ChatSender) = ChatMessage("m1", "t1", sender, "s1", "Hello", 1L)

    @Test fun countsComeFromTheSource_andAnOfflineAnswerKeepsTheLastOnes() = runTest {
        val source = Source().apply { notifications = 2; messages = 3 }
        val badges = ParentBadges(Children(hala), source)
        badges.refresh()
        assertEquals(UnreadCounts(2, 3), badges.counts.value)

        source.notifications = null; source.messages = null          // offline: nothing could be asked
        badges.refresh()
        assertEquals(UnreadCounts(2, 3), badges.counts.value)
    }

    @Test fun noChild_noBadges() = runTest {
        val badges = ParentBadges(Children(null), Source().apply { notifications = 5 })
        badges.refresh()
        assertEquals(UnreadCounts(), badges.counts.value)
    }

    @Test fun aStaffMessageOrANotificationFrameMovesTheBadgesLive() = runTest {
        val source = Source()
        val frames = MutableSharedFlow<ChatFrame>()
        val badges = ParentBadges(Children(hala), source)
        badges.start(backgroundScope, frames)
        runCurrent()

        source.messages = 1
        frames.emit(ChatFrame.Message(message(ChatSender.TEACHER)))
        runCurrent()
        assertEquals(1, badges.counts.value.messages)

        source.notifications = 4
        frames.emit(ChatFrame.Notification(NotificationView("n1", NotificationKind.BROADCAST_POSTED, "Parents evening", createdAt = 1L)))
        runCurrent()
        assertEquals(4, badges.counts.value.notifications)
    }

    @Test fun theParentsOwnEchoAndTypingDoNotAskTheServer() {
        assertFalse(ParentBadges.movesBadges(ChatFrame.Message(message(ChatSender.PARENT))))
        assertFalse(ParentBadges.movesBadges(ChatFrame.Typing("t1", ChatSender.TEACHER)))
        assertFalse(ParentBadges.movesBadges(ChatFrame.Presence(online = true, userId = "u")))
        assertTrue(ParentBadges.movesBadges(ChatFrame.Read("t1", ChatSender.PARENT, 1L)))
    }
}
