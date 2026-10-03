package quest.feature.notifications

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import quest.api.DEFAULT_FLAGS
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatTopic
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.notifications.data.ParentUnreadSource
import quest.feature.school.domain.FlagStore
import quest.feature.school.presentation.LocalFlags
import quest.ui.design.DashboardBottomNavigation
import quest.ui.design.DashboardTab
import kotlin.test.assertFalse
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.ChatFrame
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.WeeklyPlanArchive
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.broadcasts.presentation.BroadcastsContract
import quest.feature.broadcasts.presentation.BroadcastsViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.notifications.domain.NotificationsRepository
import quest.feature.notifications.domain.rowsFor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** M4 (D5) on B3's contract: her notification rows on the tab, marked read, followed, and live. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsTabTest {
    private val hala = Child("c1", "Hala", "sun", Curriculum.BRITISH, 1)
    private fun row(id: String, kind: NotificationKind, link: String?, childId: String? = "c1", read: Boolean = false) =
        NotificationView(id, kind, "Title $id", "Body", link, readAt = if (read) 1L else null, createdAt = 1L, childId = childId)

    private class Children(child: Child) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class Feed : BroadcastsRepository {
        var loads = 0
        override suspend fun feed(childId: String): BroadcastFeed { loads++; return BroadcastFeed(0, emptyList()) }
        override suspend fun plans(childId: String): WeeklyPlanArchive = error("not used")
        override suspend fun markRead(childId: String, broadcastId: String): BroadcastView = error("not used")
    }

    private class Rows(var rows: List<NotificationView>) : NotificationsRepository {
        val marked = mutableListOf<String>()
        override suspend fun rows(childId: String) = rowsFor(childId, rows)
        override suspend fun markRead(id: String): NotificationView { marked += id; return rows.first { it.id == id }.copy(readAt = 2L) }
    }

    private val built = mutableListOf<BroadcastsViewModel>()
    @AfterTest fun tearDown() { built.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }

    @Test fun theTabListsTheCurrentChildsRowsOnly() {
        val rows = listOf(row("a", NotificationKind.CHAT_MESSAGE, null), row("b", NotificationKind.EXAM_RELEASED, null, childId = "c2"))
        assertEquals(listOf("a"), rowsFor("c1", rows).map { it.id })
    }

    @Test fun openingARowMarksItReadAndFollowsIt_andANotificationFrameReloadsTheTab() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val frames = MutableSharedFlow<ChatFrame>()
        val rows = Rows(listOf(row("r1", NotificationKind.EXAM_RELEASED, "/children/c1/progress")))
        val feed = Feed()
        val vm = BroadcastsViewModel(Children(hala), feed, frames, badges = null, notifications = rows).also { built += it }
        val effects = mutableListOf<BroadcastsContract.Effect>()
        backgroundScope.launch { vm.effects.collect { effects += it } }
        vm.dispatch(BroadcastsContract.Intent.Load); runCurrent()
        assertEquals(listOf("r1"), vm.state.value.updates.map { it.id })

        vm.dispatch(BroadcastsContract.Intent.OpenUpdate("r1")); runCurrent()
        assertEquals(listOf("r1"), rows.marked)
        assertNotNull(vm.state.value.updates.single().readAt)
        assertEquals(listOf<BroadcastsContract.Effect>(BroadcastsContract.Effect.Follow(rows.rows.single())), effects, "followed through the one router")

        rows.rows = rows.rows + row("r2", NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t")
        val before = feed.loads
        frames.emit(ChatFrame.Notification(row("r2", NotificationKind.CHAT_MESSAGE, null))); runCurrent()
        assertEquals(before + 1, feed.loads, "a notification frame while the tab is open reloads it")
        assertEquals(2, vm.state.value.updates.size)
    }

    // ---- M5 (the owner, 2026-10-03): the Notifications tab never disappears

    @OptIn(ExperimentalTestApi::class)
    @Test fun theBottomBarHasTheNotificationsTabWithEveryFlagOff() = runComposeUiTest {
        val off = object : FlagStore { override val flags = MutableStateFlow(DEFAULT_FLAGS.keys.associateWith { false }) }
        setContent { CompositionLocalProvider(LocalFlags provides off) { DashboardBottomNavigation(DashboardTab.HOME, {}) } }
        onNodeWithText(DashboardTab.NOTIFICATION.labelEn).assertExists()
    }

    @Test fun withoutAnnouncementsTheTabStillListsHerRowsAndReadsNoFeed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val feed = Feed()
        val rows = Rows(listOf(row("r1", NotificationKind.EXAM_RELEASED, "/children/c1/progress")))
        val vm = BroadcastsViewModel(Children(hala), feed, notifications = rows, feedOn = { false }).also { built += it }
        vm.dispatch(BroadcastsContract.Intent.Load); runCurrent()
        assertEquals(listOf("r1"), vm.state.value.updates.map { it.id })
        assertEquals(0, feed.loads, "no announcements, no feed request")
        assertEquals(null, vm.state.value.errorMessage)
        assertFalse(vm.state.value.loading)
    }

    @Test fun withoutAnnouncementsTheBadgeStillCountsHerUnreadRows() = runTest {
        val rows = Rows(listOf(row("a", NotificationKind.CHAT_MESSAGE, null), row("b", NotificationKind.EXAM_RELEASED, null, read = true)))
        val feed = Feed()
        val source = ParentUnreadSource(feed, rows, NoChat, announcementsOn = { false }, chatOn = { false })
        assertEquals(1, source.notifications("c1"))
        assertEquals(0, feed.loads)
    }

    private object NoChat : ChatRepository {
        override val connectionState: StateFlow<ChatConnectionState> = MutableStateFlow(ChatConnectionState.DISCONNECTED)
        override val incomingFrames = MutableSharedFlow<ChatFrame>()
        override suspend fun threads(childId: String): List<ChatThread> = emptyList()
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic?): ChatMessage = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) = Unit
        override suspend fun sendTyping(childId: String, teacherId: String) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit
    }
}
