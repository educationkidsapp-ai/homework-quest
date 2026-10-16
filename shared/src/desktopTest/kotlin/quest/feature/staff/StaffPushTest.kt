package quest.feature.staff

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.AttachmentRef
import quest.api.dto.BroadcastFeed
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatPeerRole
import quest.api.dto.ChatThread
import quest.api.dto.Child
import quest.api.dto.Complaint
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintList
import quest.api.dto.ComplaintRecipient
import quest.api.dto.BroadcastView
import quest.api.dto.DevicePlatform
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.PushMessage
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.WeeklyPlanArchive
import quest.core.navigation.Routes
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.chat.domain.StagedUpload
import quest.feature.children.domain.ChildrenRepository
import quest.feature.complaints.domain.ComplaintsRepository
import quest.feature.notifications.domain.NotificationsRepository
import quest.feature.push.domain.Destination
import quest.feature.push.domain.NotificationRouter
import quest.feature.push.domain.NotificationTap
import quest.feature.push.domain.ParentGate
import quest.feature.push.domain.PushChannel
import quest.feature.push.domain.PushPayload
import quest.feature.push.domain.PushPreferences
import quest.feature.push.domain.PushRegistrar
import quest.feature.push.domain.PushRegistration
import quest.feature.push.domain.PushTokens
import quest.feature.push.domain.decidableRequestId
import quest.feature.push.presentation.PushNavigator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 13: the staff half of push.
 *
 * Three things have to hold. Her device registers with **her** bearer, so the token lands in `staff_devices`; it is
 * withdrawn the same way when she signs out; and a tapped `chat.message` opens her conversation — but only once the
 * **app lock** is past, because a staff member has no parent gate and her phone's lock is the whole of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaffPushTest {

    private class Session(signedIn: Boolean) : AuthProvider {
        private val _state = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn("u-1", "t@x.com") else AuthState.SignedOut)
        override val state: StateFlow<AuthState> = _state
        override suspend fun signIn(email: String, password: String) { _state.value = AuthState.SignedIn("u-1", email) }
        override suspend fun signOut() { _state.value = AuthState.SignedOut }
        override suspend fun idToken(forceRefresh: Boolean): String? = (state.value as? AuthState.SignedIn)?.let { "token" }
    }

    private class Registrar(val name: String) : PushRegistrar {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        override suspend fun register(device: RegisterDeviceRequest) { registered += device.token }
        override suspend fun unregister(token: String) { unregistered += token }
    }

    private object Tokens : PushTokens {
        override val platform = DevicePlatform.ANDROID
        override suspend fun token(): String = "fcm-token"
        override suspend fun delete() = Unit
    }

    private class Preferences : PushPreferences {
        private var token: String? = null
        override suspend fun registeredToken(): String? = token
        override suspend fun setRegisteredToken(token: String?) { this.token = token }
        override suspend fun promptAnswered(): Boolean = true
        override suspend fun setPromptAnswered() = Unit
    }

    /** A staff push: `kind=chat.message` with a `threadId` and no `childId` at all. */
    private fun staffPush(threadId: String) = PushMessage(
        kind = NotificationKind.CHAT_MESSAGE, title = "Message from Hassan Ali", body = "Good morning",
        notificationId = "n-1", collapseKey = "chat:$threadId", threadId = threadId,
    ).toData()

    @Test fun `a staff push carries its thread and no child`() {
        val notice = PushPayload.parse(staffPush("th-1"))
        assertTrue(notice != null)
        assertEquals(PushChannel.MESSAGES, notice.channel)
        assertEquals("th-1", notice.tap.threadId)
        assertNull(notice.tap.childId)
    }

    @Test fun `the token registers with the staff side on sign-in and leaves with it on sign-out`() = runTest {
        val parentAuth = Session(signedIn = false)
        val staffAuth = Session(signedIn = true)
        val parent = Registrar("parent")
        val staff = Registrar("staff")
        val preferences = Preferences()
        val registration = PushRegistration(
            parentAuth, parent, Tokens, preferences, locale = { "ar" },
            staff = { if (staffAuth.state.value is AuthState.SignedIn) staffAuth to staff else null },
        )

        registration.signedIn()
        assertEquals(listOf("fcm-token"), staff.registered, "a staff session registers through her own bearer")
        assertTrue(parent.registered.isEmpty(), "and never through the parent's")
        assertEquals(PushRegistration.Status.REGISTERED, registration.status.value)

        registration.signingOut()
        assertEquals(listOf("fcm-token"), staff.unregistered)
        assertEquals(PushRegistration.Status.OFF, registration.status.value)
    }

    /** With no staff session the registration is the parent's, exactly as it was before feature 13. */
    @Test fun `a parent-only app still registers through the parent side`() = runTest {
        val parent = Registrar("parent")
        val staff = Registrar("staff")
        val registration = PushRegistration(
            Session(signedIn = true), parent, Tokens, Preferences(), locale = { "en" }, staff = { null },
        )
        registration.signedIn()
        assertEquals(listOf("fcm-token"), parent.registered)
        assertTrue(staff.registered.isEmpty())
    }

    @Test fun `a tapped staff push opens her conversation, and only after the app lock`() = runTest {
        val thread = ChatThread(
            id = "th-1", childId = "c-1", childName = "Lina", teacherId = "u-1", teacherName = "Ms Noor",
            peerRole = ChatPeerRole.PARENT, parentName = "Hassan Ali",
        )
        val router = router(staffThreads = listOf(thread))
        var unlocked = false
        val unlock = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
        val navigator = PushNavigator(
            router, ParentGate(elapsed = { 0L }), signedIn = { true },
            awaitUnlocked = { unlock.first() },
        )

        val tap = NotificationTap("chat.message", notificationId = "n-1", threadId = "th-1")
        var step: PushNavigator.Step? = null
        val job = launch { step = navigator.follow(tap) }
        runCurrent()
        assertNull(step, "a locked app does not open the conversation underneath the lock's cover")

        unlocked = true
        unlock.emit(Unit)
        advanceUntilIdle()
        job.join()
        val opened = assertIs<PushNavigator.Step.Staff>(step)
        assertEquals(Routes.StaffConversation(threadId = "th-1"), opened.route)
        assertTrue(unlocked)
    }

    /** A thread that is not one of hers is not a staff tap: the parent road, gate and all, is taken instead. */
    @Test fun `a thread she is not on falls through to the parent gate`() = runTest {
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true })
        val step = navigator.follow(NotificationTap("chat.message", threadId = "th-other"))
        assertEquals(PushNavigator.Step.Gate, step)
    }

    @Test fun `a signed-out app follows nothing`() = runTest {
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { false })
        assertEquals(PushNavigator.Step.Nothing, navigator.follow(NotificationTap("chat.message", threadId = "th-1")))
    }

    @Test fun `the router answers a staff destination for a thread of hers`() = runTest {
        val thread = ChatThread(id = "th-1", childId = "", childName = "", teacherId = "mgr", teacherName = "Mr Adel", peerRole = ChatPeerRole.MANAGERIAL)
        val destination = router(staffThreads = listOf(thread)).resolve(NotificationTap("chat.message", threadId = "th-1"))
        val staff = assertIs<Destination.StaffConversation>(destination)
        assertEquals("th-1", staff.thread.id)
    }

    /** No staff session at all: the staff lookup answers null and nothing about the parent side changes. */
    @Test fun `without a staff session the tap is the parent's`() = runTest {
        val destination = router(staffThreads = null).resolve(NotificationTap("chat.message", threadId = "th-1", notificationId = "n-9"))
        assertIs<Destination.Notifications>(destination)
    }

    // ---------------------------------------------------------------- feature 16: attendance

    @Test fun `an attendance reminder opens her Attendance tab, after the app lock`() = runTest {
        var locked = true
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true },
            awaitUnlocked = { locked = false })
        val step = navigator.follow(NotificationTap("attendance.reminder", link = "/notifications?attendance=start_reminder", outside = true))
        assertEquals(PushNavigator.Step.Staff(Routes.StaffHome), step, "her Attendance tab, not the parent gate")
        assertTrue(!locked, "and only once the app lock was past")
    }

    @Test fun `an attendance alert or the digest opens her notifications until feature 17`() = runTest {
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true })
        assertEquals(PushNavigator.Step.Staff(Routes.StaffNotifications),
            navigator.follow(NotificationTap("attendance.alert", link = "/notifications?attendance=late&minutes=18")))
        assertEquals(PushNavigator.Step.Staff(Routes.StaffNotifications),
            navigator.follow(NotificationTap("attendance.digest", link = "/notifications?attendance=digest")))
    }

    @Test fun `an attendance tap without a staff session is not a staff tap`() = runTest {
        val navigator = PushNavigator(router(staffThreads = null, staffSignedIn = false), ParentGate(elapsed = { 0L }), signedIn = { true })
        assertEquals(PushNavigator.Step.Gate, navigator.follow(NotificationTap("attendance.reminder", outside = true)))
    }

    @Test fun `an attendance push is parsed with its kind and link`() {
        val data = PushMessage(
            kind = NotificationKind.ATTENDANCE_ALERT, title = "الحضور", body = "أ. خالد تأخر 18 دقيقة", notificationId = "n-7",
            link = "/notifications?attendance=late&minutes=18", collapseKey = "attendance:late:u-1:2026-10-18",
        ).toData()
        val notice = PushPayload.parse(data)
        assertTrue(notice != null)
        assertEquals("attendance.alert", notice.tap.kind)
        assertEquals("/notifications?attendance=late&minutes=18", notice.tap.link)
    }

    // ------------------------------------------- feature 25: the morning brief (post-merge review)

    @Test fun `the morning brief opens her staff home, after the app lock, and never the parent PIN`() = runTest {
        // `MorningBriefJob` rings each leader through `staffNotice`, which pushes to her staff devices while
        // `staffMobileApp` is on. The brief names no child and no thread and its link is "/", so before this it fell
        // through to the parent road and a manager landed on a PIN screen she has none for.
        var locked = true
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true },
            awaitUnlocked = { locked = false })
        val step = navigator.follow(NotificationTap("morning.brief", link = "/", outside = true))
        assertEquals(PushNavigator.Step.Staff(Routes.StaffHome), step, "her staff home, not Routes.ParentPin")
        assertTrue(!locked, "and only once the app lock was past")
    }

    @Test fun `a morning brief without a staff session is still the parent's road`() = runTest {
        val navigator = PushNavigator(router(staffThreads = null, staffSignedIn = false), ParentGate(elapsed = { 0L }), signedIn = { true })
        assertEquals(PushNavigator.Step.Gate, navigator.follow(NotificationTap("morning.brief", link = "/", outside = true)))
    }

    // ---------------------------------------------------------------- feature 19: chat office hours

    @Test fun `a message outside her office hours is quiet, and the same message inside them is not`() {
        fun channel(quiet: Boolean) = PushPayload.parse(
            PushMessage(
                kind = NotificationKind.CHAT_MESSAGE, title = "Message from Hassan Ali", body = "Good evening",
                collapseKey = "chat:th-1", threadId = "th-1", quiet = quiet,
            ).toData(),
        )!!.channel
        assertEquals(PushChannel.MESSAGES_QUIET, channel(quiet = true))
        assertEquals(PushChannel.MESSAGES, channel(quiet = false), "nothing changes inside her hours")
        assertTrue(PushChannel.MESSAGES_QUIET.quiet, "no sound and no vibration on that channel")
        assertFalse(PushChannel.MESSAGES.quiet)
    }

    /** A complaint keeps its own channel whatever the hour (milestone 12), even when the server marks it quiet. */
    @Test fun `a quiet complaint still rings on Complaints`() {
        assertEquals(PushChannel.COMPLAINTS, PushPayload.channelOf("complaint.message", quiet = true))
    }

    @Test fun `the morning summary opens her Messages tab, after the app lock`() = runTest {
        var locked = true
        val navigator = PushNavigator(
            router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true },
            awaitUnlocked = { locked = false },
        )
        val tap = NotificationTap("chat.after_hours_summary", link = "/staff/chat/threads?count=3", notificationId = "n-3", outside = true)
        assertEquals(PushNavigator.Step.Staff(Routes.StaffMessages), navigator.follow(tap), "her Messages tab, not the parent gate")
        assertTrue(!locked, "and only once the app lock was past")
        assertIs<Destination.StaffMessages>(router(staffThreads = emptyList()).resolve(tap))
    }

    @Test fun `a summary without a staff session is not a staff tap`() = runTest {
        val navigator = PushNavigator(router(staffThreads = null, staffSignedIn = false), ParentGate(elapsed = { 0L }), signedIn = { true })
        assertEquals(PushNavigator.Step.Gate, navigator.follow(NotificationTap("chat.after_hours_summary", link = "/staff/chat/threads?count=2", outside = true)))
    }

    // ---------------------------------------------------------------- feature 18: requests and decisions

    @Test fun `a decision on her own request opens her record, where the corrected day is`() = runTest {
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true })
        val link = "/notifications?attendance=correction_decided&request=r-1&decision=approved&date=2026-10-13"
        assertEquals(
            PushNavigator.Step.Staff(Routes.StaffMyRecord),
            navigator.follow(NotificationTap("attendance.decision", link = link, outside = true)),
        )
    }

    @Test fun `a request to decide and a remote day open her notifications`() = runTest {
        val navigator = PushNavigator(router(staffThreads = emptyList()), ParentGate(elapsed = { 0L }), signedIn = { true })
        assertEquals(
            PushNavigator.Step.Staff(Routes.StaffNotifications),
            navigator.follow(NotificationTap("attendance.request", link = "/notifications?attendance=remote_day_request&request=r-1", outside = true)),
        )
        assertEquals(
            PushNavigator.Step.Staff(Routes.StaffNotifications),
            navigator.follow(NotificationTap("attendance.remote_day", link = "/notifications?attendance=remote_day&date=2026-10-20", outside = true)),
        )
    }

    /** The request's id survives the FCM data map, which is the whole of what the Approve button needs. */
    @Test fun `a request push keeps its link and its request id`() {
        val data = PushMessage(
            kind = NotificationKind.ATTENDANCE_REQUEST, title = "الحضور", body = "أ. خالد يطلب العمل عن بعد",
            notificationId = "n-18", collapseKey = "attendance:request:r-1",
            link = "/notifications?attendance=remote_day_request&request=r-1&staff=USER%3Au-1&name=%D8%A3.+%D8%AE%D8%A7%D9%84%D8%AF&date=2026-10-20",
        ).toData()
        val notice = PushPayload.parse(data)
        assertTrue(notice != null)
        assertEquals("attendance.request", notice.tap.kind)
        assertEquals("attendance:request:r-1", notice.tag)
        assertEquals("r-1", decidableRequestId(notice.tap.kind, notice.tap.link))
    }

    /** Only a request a reader may settle gets the two buttons; a decision and a reminder get none. */
    @Test fun `which notice offers Approve and Reject is decided by the kind and the link`() {
        assertEquals("r-7", decidableRequestId("attendance.request", "/notifications?attendance=correction_request&request=r-7"))
        assertNull(decidableRequestId("attendance.request", "/notifications?attendance=correction_request"))
        assertNull(decidableRequestId("attendance.decision", "/notifications?attendance=correction_decided&request=r-7"))
        assertNull(decidableRequestId("attendance.reminder", "/notifications?attendance=start_reminder"))
        assertNull(decidableRequestId(null, null))
    }

    // ---------------------------------------------------------------- the parent side's doubles, unchanged

    private fun router(staffThreads: List<ChatThread>?, staffSignedIn: Boolean = staffThreads != null) = NotificationRouter(
        children = NoChildren, chat = NoChat, notifications = NoNotifications, broadcasts = NoBroadcasts,
        complaints = NoComplaints, lessonsOnMap = { null }, staffThreads = { staffThreads }, staffSignedIn = { staffSignedIn },
    )

    private object NoChildren : ChildrenRepository {
        override val currentChild = MutableStateFlow<Child?>(null)
        override suspend fun refresh(): List<Child> = emptyList()
        override suspend fun children(): List<Child> = emptyList()
        override suspend fun select(id: String) = Unit
        override suspend fun clear() = Unit
    }

    private object NoChat : ChatRepository {
        override val connectionState = MutableStateFlow(ChatConnectionState.DISCONNECTED)
        override val incomingFrames: SharedFlow<ChatFrame> = MutableSharedFlow()
        override suspend fun threads(childId: String): List<ChatThread> = emptyList()
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage = error("not used")
        override suspend fun uploadAttachment(childId: String, file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) = Unit
        override suspend fun sendTyping(childId: String, teacherId: String) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit
    }

    private object NoNotifications : NotificationsRepository {
        override suspend fun rows(childId: String): List<NotificationView> = emptyList()
        override suspend fun markRead(id: String): NotificationView = error("not used")
    }

    private object NoBroadcasts : BroadcastsRepository {
        override suspend fun feed(childId: String): BroadcastFeed = BroadcastFeed(0, emptyList())
        override suspend fun markRead(childId: String, broadcastId: String): BroadcastView = error("not used")
        override suspend fun plans(childId: String): WeeklyPlanArchive = WeeklyPlanArchive(from = "2026-10-05", to = "2026-10-11")
    }

    private object NoComplaints : ComplaintsRepository {
        override suspend fun list(childId: String, status: String?): ComplaintList = ComplaintList(emptyList())
        override suspend fun recipients(childId: String): List<ComplaintRecipient> = emptyList()
        override suspend fun create(childId: String, staffId: String, title: String, body: String, clientId: String, attachmentIds: List<String>): ComplaintDetail = error("not used")
        override suspend fun detail(childId: String, complaintId: String, since: String?): ComplaintDetail = error("not used")
        override suspend fun reply(childId: String, complaintId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage = error("not used")
        override suspend fun markRead(childId: String, complaintId: String) = Unit
        override suspend fun reopen(childId: String, complaintId: String): Complaint = error("not used")
    }
}
