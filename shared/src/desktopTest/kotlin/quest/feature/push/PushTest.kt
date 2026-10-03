package quest.feature.push

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.BroadcastFeed
import quest.api.dto.BroadcastView
import quest.api.dto.ChatFrame
import quest.api.dto.ChatMessage
import quest.api.dto.ChatThread
import quest.api.dto.ChatTopic
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.DevicePlatform
import quest.api.dto.NotificationKind
import quest.api.dto.NotificationView
import quest.api.dto.PushMessage
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.WeeklyPlanArchive
import quest.core.json.AppJson
import quest.core.navigation.Routes
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.lock.domain.AppLock
import quest.feature.notifications.domain.NotificationsRepository
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.ChatPeerRole
import quest.api.dto.Complaint
import quest.api.dto.ComplaintDetail
import quest.api.dto.ComplaintList
import quest.feature.complaints.domain.ComplaintsRepository
import quest.api.dto.BroadcastKind
import quest.api.dto.ChatStaffRole
import quest.api.dto.ChatThreadStatus
import quest.api.dto.WeeklyPlanEntry
import quest.api.dto.WeeklyPlanWeek
import quest.feature.push.domain.NotificationRouter
import quest.feature.push.domain.NotificationTap
import quest.feature.push.domain.LinkShape
import quest.feature.push.domain.isNewLaunch
import quest.feature.push.domain.linkShape
import quest.feature.push.domain.childOf
import quest.feature.push.domain.staffOf
import quest.feature.push.domain.openOf
import quest.feature.parent.presentation.asConversation
import quest.feature.push.domain.ParentGate
import quest.feature.push.domain.PushChannel
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.PushPayload
import quest.feature.push.domain.PushPreferences
import quest.feature.push.domain.PushPrompts
import quest.feature.push.domain.PushRegistrar
import quest.feature.push.domain.PushRegistration
import quest.feature.push.domain.PushTokens
import quest.feature.push.presentation.PushNavigator
import quest.feature.lock.domain.BiometricChoice
import quest.feature.lock.domain.BiometricPreferences
import quest.core.platform.BiometricAuthenticator
import quest.core.platform.BiometricKind
import quest.core.platform.BiometricResult
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M5 — push for parents: the token's life, the payload as the shade shows it, the permission card, and the taps. */
@OptIn(ExperimentalCoroutinesApi::class)
class PushTest {
    // ---- fakes ---------------------------------------------------------------------------------------------------

    private class Auth(signedIn: Boolean) : AuthProvider {
        val flow = MutableStateFlow<AuthState>(if (signedIn) AuthState.SignedIn("uid-1", "p@school.test") else AuthState.SignedOut)
        override val state: StateFlow<AuthState> = flow
        override suspend fun signIn(email: String, password: String) { flow.value = AuthState.SignedIn("uid-1", email) }
        override suspend fun signOut() { flow.value = AuthState.SignedOut }
        override suspend fun idToken(forceRefresh: Boolean): String? = if (flow.value is AuthState.SignedIn) "bearer" else null
    }

    private class Server(var failing: Boolean = false) : PushRegistrar {
        val devices = mutableMapOf<String, RegisterDeviceRequest>()
        val calls = mutableListOf<String>()
        override suspend fun register(device: RegisterDeviceRequest) { calls += "POST ${device.token}"; if (failing) error("offline"); devices[device.token] = device }
        override suspend fun unregister(token: String) { calls += "UNREGISTER $token"; devices.remove(token) }
    }

    private class Tokens(override val platform: DevicePlatform? = DevicePlatform.ANDROID, var current: String? = "fcm-1") : PushTokens {
        var deleted = 0
        override val appVersion: String = "0.1.0"
        override suspend fun token(): String? = current
        override suspend fun delete() { deleted++; current = null }
    }

    private class Prefs : PushPreferences {
        var token: String? = null
        var answered = false
        override suspend fun registeredToken() = token
        override suspend fun setRegisteredToken(token: String?) { this.token = token }
        override suspend fun promptAnswered() = answered
        override suspend fun setPromptAnswered() { answered = true }
    }

    private class Setup(signedIn: Boolean = true, platform: DevicePlatform? = DevicePlatform.ANDROID) {
        val auth = Auth(signedIn); val server = Server(); val tokens = Tokens(platform); val prefs = Prefs()
        var language = "en"
        val registration = PushRegistration(auth, server, tokens, prefs, locale = { language })
    }

    // ---- registration --------------------------------------------------------------------------------------------

    @Test fun aSignedInParentRegistersTheTokenWithPlatformVersionAndLanguage() = runTest {
        val s = Setup().apply { language = "ar" }
        s.registration.signedIn()
        assertEquals(RegisterDeviceRequest("fcm-1", DevicePlatform.ANDROID, "0.1.0", "ar"), s.server.devices["fcm-1"])
        assertEquals("fcm-1", s.prefs.token)
        assertEquals(PushRegistration.Status.REGISTERED, s.registration.status.value)
    }

    @Test fun aSignedOutAppNeverRegisters() = runTest {
        val s = Setup(signedIn = false)
        s.registration.signedIn()
        s.registration.newToken("fcm-2")                     // Firebase may hand one over at first launch
        assertTrue(s.server.calls.isEmpty())
        assertNull(s.prefs.token)
        assertEquals(PushRegistration.Status.OFF, s.registration.status.value)
    }

    @Test fun withoutPushOnThePlatformNothingIsAskedOrSent() = runTest {
        val s = Setup(platform = null)
        s.registration.signedIn(); s.registration.newToken("x"); s.registration.signingOut()
        assertTrue(s.server.calls.isEmpty())
        assertEquals(0, s.tokens.deleted)
    }

    @Test fun aNewTokenReplacesTheOldOneOnTheServer() = runTest {
        val s = Setup()
        s.registration.signedIn()
        s.registration.newToken("fcm-2")
        assertEquals(listOf("POST fcm-1", "POST fcm-2", "UNREGISTER fcm-1"), s.server.calls)
        assertEquals(setOf("fcm-2"), s.server.devices.keys)
        assertEquals("fcm-2", s.prefs.token)
    }

    @Test fun anOfflineRegistrationStaysPendingAndIsRetriedAtTheNextLaunch() = runTest {
        val s = Setup()
        s.server.failing = true
        s.registration.signedIn()
        assertEquals(PushRegistration.Status.PENDING, s.registration.status.value)
        assertNull(s.prefs.token)
        s.server.failing = false
        s.registration.signedIn()
        assertEquals(PushRegistration.Status.REGISTERED, s.registration.status.value)
    }

    @Test fun signingOutWithdrawsTheTokenThenForgetsIt() = runTest {
        val s = Setup()
        s.registration.signedIn()
        s.registration.signingOut()
        assertEquals(listOf("POST fcm-1", "UNREGISTER fcm-1"), s.server.calls)
        assertEquals(1, s.tokens.deleted)
        assertNull(s.prefs.token)
        assertEquals(PushRegistration.Status.OFF, s.registration.status.value)
    }

    @Test fun anExpiredSessionForgetsTheTokenOnTheDeviceOnly() = runTest {
        val s = Setup()
        s.registration.signedIn()
        s.auth.flow.value = AuthState.SignedOut               // Firebase refused the refresh token
        s.registration.sessionExpired()
        assertEquals(listOf("POST fcm-1"), s.server.calls, "no bearer is left to unregister with")
        assertEquals(1, s.tokens.deleted)
        assertNull(s.prefs.token)
    }

    @Test fun aLanguageChangeRegistersAgainInTheNewLanguage() = runTest {
        val s = Setup()
        val languages = MutableStateFlow("en")
        val job = s.registration.start(backgroundScope, languages)
        runCurrent()                                          // background work runs on runCurrent, not advanceUntilIdle
        s.language = "ar"; languages.value = "ar"
        runCurrent()
        assertEquals("ar", s.server.devices["fcm-1"]?.locale)
        assertEquals(2, s.server.calls.size)
        job.cancel()
    }

    // ---- the permission card -------------------------------------------------------------------------------------

    @Test fun theCardAsksOnlyWhereNeededNotGrantedAndNotYetAnswered() = runTest {
        val prefs = Prefs()
        val prompts = PushPrompts(Tokens(), prefs)
        assertTrue(prompts.shouldAsk(permissionNeeded = true, granted = false))
        assertFalse(prompts.shouldAsk(permissionNeeded = false, granted = false), "Android 12 and earlier: never asked")
        assertFalse(prompts.shouldAsk(permissionNeeded = true, granted = true))
        assertFalse(PushPrompts(Tokens(platform = null), prefs).shouldAsk(permissionNeeded = true, granted = false), "no push, no card")
        prompts.answered()                                    // "Not now", or either answer in the system dialog
        assertFalse(prompts.shouldAsk(permissionNeeded = true, granted = false))
    }

    // ---- the payload ---------------------------------------------------------------------------------------------

    private fun data(kind: NotificationKind, link: String?, collapse: String, body: String? = "Hala did well today.", id: String? = "nt-1", broadcast: String? = null) =
        PushMessage(kind, "Message from Ms Maya", body, id, "c1", link, broadcast, collapse).toData()

    @Test fun eachKindHasItsChannelAndTheCollapseKeyIsTheTag() {
        val chat = PushPayload.parse(data(NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t-maya", "chat:th-1"))!!
        assertEquals(PushChannel.MESSAGES, chat.channel)
        assertEquals("chat:th-1", chat.tag)
        assertEquals("Message from Ms Maya", chat.title, "shown exactly as the server sent it")
        assertEquals("Hala did well today.", chat.body)
        assertEquals(NotificationTap("chat.message", "/children/c1/chat/t-maya", "nt-1", null, "c1", "chat:th-1"), chat.tap)
        fun channel(kind: NotificationKind, link: String, key: String) = PushPayload.parse(data(kind, link, key))!!.channel
        assertEquals(PushChannel.EXAMS, channel(NotificationKind.EXAM_RELEASED, "/children/c1/progress", "lesson:e1"))
        assertEquals(PushChannel.EXAMS, channel(NotificationKind.EXAM_PUBLISHED, "/children/c1/map", "lesson:e1"))
        assertEquals(PushChannel.HOMEWORK, channel(NotificationKind.HOMEWORK_PUBLISHED, "/children/c1/map", "lesson:l1"))
        assertEquals(PushChannel.HOMEWORK, channel(NotificationKind.QUESTION_SENT, "/children/c1/teacher-questions/q1", "question:q1"))
        assertEquals(PushChannel.COMPLAINTS, channel(NotificationKind.COMPLAINT_STATUS, "/children/c1/complaints/cp-1", "complaint:cp-1"))
        assertEquals(PushChannel.COMPLAINTS, channel(NotificationKind.COMPLAINT_MESSAGE, "/children/c1/complaints/cp-1", "complaint:cp-1"), "a staff reply rings on Complaints, not Messages")
        assertEquals(PushChannel.SCHOOL_NEWS, channel(NotificationKind.ANNOUNCEMENT_POSTED, "/children/c1/announcements?open=a1", "announcement:a1"))
        assertEquals(PushChannel.SCHOOL_NEWS, channel(NotificationKind.BROADCAST_POSTED, "/children/c1/broadcasts?open=b1", "broadcast:b1"))
        assertEquals(PushChannel.MESSAGES, PushPayload.channelOf("admin.message"), "a kind still to come lands by its name")
    }

    @Test fun aSecondMessageInTheSameThreadReplacesTheFirst() {
        val first = PushPayload.parse(data(NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t-maya", "chat:th-1", body = "One"))!!
        val second = PushPayload.parse(data(NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t-maya", "chat:th-1", body = "Two"))!!
        assertEquals(first.tag, second.tag)
    }

    @Test fun anUnknownKindIsStillShown() {
        val unknown = PushPayload.parse(mapOf("kind" to "something.new", "title" to "Sports day moved", "childId" to "c1", "notificationId" to "nt-9"))!!
        assertEquals(PushChannel.SCHOOL_NEWS, unknown.channel)
        assertEquals("Sports day moved", unknown.title)
        assertEquals("notification:nt-9", unknown.tag)
        val untitled = PushPayload.parse(mapOf("kind" to "chat.message", "body" to "Hello", "collapseKey" to "chat:t"))!!
        assertNull(untitled.title, "the notifier puts the app's generic title on it")
        assertNull(PushPayload.parse(emptyMap()))
    }

    // ---- one router: every row of the owner's table -------------------------------------------------------------

    private val hala = Child("c1", "Hala", "sun", Curriculum.BRITISH, 1)
    private val omar = Child("c2", "Omar", "moon", Curriculum.BRITISH, 3)

    private class Kids(vararg kids: Child) : ChildrenRepository {
        val list = kids.toList()
        override val currentChild = MutableStateFlow<Child?>(list.last())
        override suspend fun refresh() = list
        override suspend fun children() = list
        override suspend fun select(id: String) { currentChild.value = list.first { it.id == id } }
        override suspend fun clear() { currentChild.value = null }
    }

    private class Chat(val threads: List<ChatThread>) : ChatRepository {
        override val connectionState: StateFlow<ChatConnectionState> get() = error("not used")
        override val incomingFrames: SharedFlow<ChatFrame> = MutableSharedFlow()
        override suspend fun threads(childId: String) = threads.filter { it.childId == childId && it.staffRole == ChatStaffRole.TEACHER }
        override suspend fun coordinators(childId: String) = threads.filter { it.childId == childId && it.staffRole == ChatStaffRole.COORDINATOR }
        override suspend fun managers(childId: String): List<ChatThread> = error("offline")
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage = error("not used")
        override suspend fun uploadAttachment(childId: String, file: quest.feature.chat.domain.StagedUpload, onProgress: (Float) -> Unit): quest.api.dto.AttachmentRef = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) = Unit
        override suspend fun sendTyping(childId: String, teacherId: String) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit
    }

    /** B6's complaints as the router asks for them: one exists ("cp-1"); any other id is a 404. */
    private class Complaints(val existing: Set<String> = setOf("cp-1")) : ComplaintsRepository {
        override suspend fun list(childId: String, status: String?) = ComplaintList()
        override suspend fun recipients(childId: String) = emptyList<quest.api.dto.ComplaintRecipient>()
        override suspend fun create(childId: String, staffId: String, title: String, body: String, clientId: String, attachmentIds: List<String>): ComplaintDetail = error("not used")
        override suspend fun detail(childId: String, complaintId: String, since: String?): ComplaintDetail {
            if (complaintId !in existing) throw ApiException(ApiError(ApiError.NOT_FOUND, "No such complaint."))
            return ComplaintDetail(Complaint(complaintId, childId, "Hala", "Homework", ChatThreadStatus.RESOLVED, "co-lina", "Ms Lina", ChatPeerRole.COORDINATOR, 1L))
        }
        override suspend fun reply(childId: String, complaintId: String, body: String, clientId: String, attachmentIds: List<String>): ChatMessage = error("not used")
        override suspend fun markRead(childId: String, complaintId: String) = Unit
        override suspend fun reopen(childId: String, complaintId: String): Complaint = error("not used")
    }

    private class Rows : NotificationsRepository {
        val read = mutableListOf<String>()
        override suspend fun rows(childId: String): List<NotificationView> = emptyList()
        override suspend fun markRead(id: String): NotificationView { read += id; return NotificationView(id, NotificationKind.CHAT_MESSAGE, "t", "b", null, readAt = 1L, createdAt = 0L) }
    }

    private fun bc(id: String, kind: BroadcastKind) = BroadcastView(id = id, kind = kind, authorId = "mg", authorName = "Ms. Nour", bodyEn = "Body", createdAt = 1L)

    private class Feed(val items: List<BroadcastView>, val archive: List<BroadcastView>) : BroadcastsRepository {
        val read = mutableListOf<String>()
        override suspend fun feed(childId: String) = BroadcastFeed(0, items)
        override suspend fun plans(childId: String) = WeeklyPlanArchive("2026-07-12", "2026-09-27", weeks = archive.map { WeeklyPlanWeek(it.weekStart ?: "", listOf(WeeklyPlanEntry(it))) })
        override suspend fun markRead(childId: String, broadcastId: String): BroadcastView { read += "$childId/$broadcastId"; error("the row itself is not needed") }
    }

    private val teacherThread = ChatThread(id = "th-1", childId = "c1", childName = "Hala", teacherId = "t-maya", teacherName = "Ms Maya")
    private val complaintThread = ChatThread(id = "th-2", childId = "c1", childName = "Hala", teacherId = "co-lina", teacherName = "Ms Lina", staffRole = ChatStaffRole.COORDINATOR, topic = ChatTopic.COMPLAINT, status = ChatThreadStatus.RESOLVED)

    private class World {
        val kids = Kids(Child("c1", "Hala", "sun", Curriculum.BRITISH, 1), Child("c2", "Omar", "moon", Curriculum.BRITISH, 3))
        val rows = Rows()
        val feed: Feed
        val gate = ParentGate(elapsed = { clock }, graceMillis = 60_000)
        var clock = 0L
        var signedIn = true
        init {
            val plan = BroadcastView(id = "bc-plan", kind = BroadcastKind.WEEKLY_PLAN, authorId = "mg", authorName = "Ms. Nour", bodyEn = "Plan", weekStart = "2026-09-27", createdAt = 1L)
            val oldPlan = plan.copy(id = "bc-plan-old", weekStart = "2026-09-13")
            feed = Feed(
                items = listOf(plan, plan.copy(id = "bc-ann", kind = BroadcastKind.ANNOUNCEMENT), plan.copy(id = "bc-event", kind = BroadcastKind.EVENT)),
                archive = listOf(plan, oldPlan),
            )
        }
        fun router(threads: List<ChatThread>, onMap: Set<String>? = setOf("l1", "e1")) =
            NotificationRouter(kids, Chat(threads), rows, feed, Complaints(), lessonsOnMap = { onMap })
        fun navigator(threads: List<ChatThread>, onMap: Set<String>? = setOf("l1", "e1")) = PushNavigator(router(threads, onMap), gate, signedIn = { signedIn })
    }

    /** The owner's table, row by row: the tap, and the page it must open after the gate. */
    private val table: List<Pair<NotificationTap, (Any) -> Unit>> = listOf(
        NotificationTap("chat.message", "/children/c1/chat/t-maya", "nt-1", childId = "c1", collapseKey = "chat:th-1") to { r ->
            assertEquals("th-1", ((r as PushNavigator.Step.Parent).route as Routes.ChatConversation).threadId)
        },
        // M8 (B6): a complaint's reply and its status change open that complaint's own page, never a Messages thread.
        NotificationTap("complaint.status", "/children/c1/complaints/cp-1", "nt-2", childId = "c1", collapseKey = "complaint:cp-1", complaintId = "cp-1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Complaint("c1", "cp-1")), r)
        },
        NotificationTap("complaint.message", "/children/c1/complaints/cp-1", "nt-2b", childId = "c1", collapseKey = "complaint:cp-1", complaintId = "cp-1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Complaint("c1", "cp-1")), r)
        },
        NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-plan", "nt-3", "bc-plan", "c1", "broadcast:bc-plan") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.WeeklyPlan(focus = "bc-plan")), r)
        },
        NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-plan-old", "nt-3b", "bc-plan-old", "c1", "broadcast:bc-plan-old") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.WeeklyPlan(focus = "bc-plan-old")), r, "an earlier week's plan is in the archive")
        },
        NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-ann", "nt-4", "bc-ann", "c1", "broadcast:bc-ann") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Broadcasts(focusBroadcast = "bc-ann")), r)
        },
        NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-event", "nt-5", "bc-event", "c1", "broadcast:bc-event") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Broadcasts(focusBroadcast = "bc-event")), r)
        },
        NotificationTap("announcement.posted", "/children/c1/announcements?open=a1", "nt-6", childId = "c1", collapseKey = "announcement:a1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-6")), r, "the class note opened in full")
        },
        NotificationTap("question.sent", "/children/c1/teacher-questions/q1", "nt-7", childId = "c1", collapseKey = "question:q1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-7")), r)
        },
        NotificationTap("homework.published", "/children/c1/map", "nt-8", childId = "c1", collapseKey = "lesson:l1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.LessonPanel("l1")), r, "the parent's view of that lesson")
        },
        NotificationTap("exam.published", "/children/c1/map", "nt-9", childId = "c1", collapseKey = "lesson:e1") to { r ->
            assertEquals(PushNavigator.Step.Child(null), r, "the child's home, where the exam's card is")
        },
        NotificationTap("exam.released", "/children/c1/progress", "nt-10", childId = "c1", collapseKey = "lesson:e1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Progress(focusExam = "e1")), r, "the parent's result, with its score")
        },
        NotificationTap("something.new", null, "nt-11", childId = "c1") to { r ->
            assertEquals(PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-11")), r, "unknown: the Notifications page, that row highlighted")
        },
    )

    private enum class Start { COLD, BACKGROUND, FOREGROUND }

    /**
     * Every row × cold start / background / foreground × biometrics on / off. Cold start and background (more than the
     * grace away) always meet the gate first; so does a tap in the foreground on the child's side. Only a tap inside the
     * parent area, gate passed a moment ago, goes straight on. The gate itself is the biometric where it is on and the
     * PIN where it is off — the PIN screen's own rule (`AppLock.confirmOwner`, then the pad).
     */
    @Test fun everyRowOpensItsOwnPageOnlyAfterTheParentGate() = runTest {
        for ((tap, expect) in table) for (start in Start.entries) for (biometric in listOf(true, false)) {
            val w = World()
            val nav = w.navigator(listOf(teacherThread, complaintThread))
            val label = "${tap.kind} ${tap.broadcastId.orEmpty()} $start biometric=$biometric"
            when (start) {
                Start.COLD -> Unit                                          // a fresh process: nothing passed yet
                Start.BACKGROUND -> { w.gate.passed(); w.gate.away(); w.clock += 61_000; w.gate.back() }
                Start.FOREGROUND -> w.gate.closed()                         // on the child's side
            }
            assertEquals(PushNavigator.Step.Gate, nav.follow(tap), "$label: the gate comes first")
            assertEquals("c2", w.kids.currentChild.value?.id, "$label: nothing is selected before the gate")
            val prompt = Prompt(BiometricResult.SUCCESS)
            val lock = AppLock(Auth(signedIn = true), if (biometric) LockPrefs() else NoLockPrefs(), prompt, signOut = {}, elapsed = { 0L })
            if (start == Start.COLD) lock.coldStart()
            if (lock.state.value.stage == AppLock.Stage.LOCKED) lock.unlock("Unlock")
            assertEquals(biometric, lock.confirmOwner("Parent area"), "$label: biometric when on; the PIN pad otherwise")
            w.gate.passed()                                                 // the biometric, or the PIN, confirmed her
            expect(nav.afterGate(tap))
            assertEquals("c1", w.kids.currentChild.value?.id, "$label: switched to the child the tap is about")
            assertTrue(tap.notificationId!! in w.rows.read, "$label: the row is marked read")
        }
    }

    /** The owner's rule (2026-10-03): a tap in the system shade always shows the gate — even right after it was passed. */
    @Test fun aTapFromOutsideTheAppAlwaysMeetsTheGate() = runTest {
        for ((tap, expect) in table) {
            val w = World()
            w.gate.passed()                                                 // inside the parent area, gate open
            val nav = w.navigator(listOf(teacherThread, complaintThread))
            assertEquals(PushNavigator.Step.Gate, nav.follow(tap.copy(outside = true)), "${tap.kind}: no grace for a push")
            w.gate.passed()
            expect(nav.afterGate(tap.copy(outside = true)))
        }
    }

    /** Homework and a released exam open the parent's own pages, inside the parent area — never the child's side. */
    @Test fun homeworkAndResultsOpenTheParentsViewNotTheChilds() = runTest {
        val w = World().apply { gate.passed() }
        val nav = w.navigator(emptyList())
        val homework = nav.follow(NotificationTap("homework.published", "/children/c1/map", "nt-8", childId = "c1", collapseKey = "lesson:l1"))
        val result = nav.follow(NotificationTap("exam.released", "/children/c1/progress", "nt-10", childId = "c1", collapseKey = "lesson:e1"))
        assertEquals(PushNavigator.Step.Parent(Routes.LessonPanel("l1")), homework)
        assertEquals(PushNavigator.Step.Parent(Routes.Progress(focusExam = "e1")), result)
        assertEquals("c1", w.kids.currentChild.value?.id, "for the right child")
    }

    @Test fun onlyANewLaunchFollowsTheIntent() {
        assertTrue(isNewLaunch(restored = false, fromHistory = false), "a tap that started the activity")
        assertFalse(isNewLaunch(restored = true, fromHistory = false), "recreated after process death: the old intent again")
        assertFalse(isNewLaunch(restored = false, fromHistory = true), "relaunched from Recents: the old intent again")
    }

    @Test fun insideTheParentAreaATapGoesStraightToItsPage() = runTest {
        for ((tap, expect) in table) {
            val w = World()
            w.gate.passed()
            expect(w.navigator(listOf(teacherThread, complaintThread)).follow(tap))
        }
    }

    @Test fun theGraceIsTheLockMinute() = runTest {
        val w = World()
        w.gate.passed(); w.gate.away(); w.clock += 59_000; w.gate.back()
        assertTrue(w.gate.isOpen, "back within the minute: no second prompt")
        w.gate.away(); w.clock += 61_000
        assertFalse(w.gate.isOpen, "a minute away: the gate again")
        assertEquals(AppLock.BACKGROUND_LIMIT_MILLIS, 60_000L)
    }

    @Test fun backingOutOfTheGateNeverShowsTheTarget() = runTest {
        val tap = table.first().first
        PushLinks.waitForGate(tap)
        PushLinks.abandoned()
        PushLinks.gateOpened()                                              // a later unlock from the child's side
        assertNull(PushLinks.unlocked.value, "the dropped tap is not followed after a later, unrelated unlock")
        PushLinks.waitForGate(tap)
        PushLinks.gateOpened()
        assertEquals(tap, PushLinks.unlocked.value)
    }

    @Test fun staleTargetsOpenTheNotificationsPageWithAMessage() = runTest {
        val w = World()
        w.gate.passed()
        val nav = w.navigator(threads = emptyList(), onMap = emptySet())
        val gone = PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-1", gone = true))
        assertEquals(gone, nav.follow(NotificationTap("chat.message", "/children/c1/chat/t-gone", "nt-1", childId = "c1", collapseKey = "chat:th-gone")), "deleted thread")
        assertEquals(gone, nav.follow(NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-x", "nt-1", "bc-x", "c1")), "expired event")
        assertEquals(gone, nav.follow(NotificationTap("homework.published", "/children/c1/map", "nt-1", childId = "c1", collapseKey = "lesson:l-gone")), "lesson gone")
        assertEquals(gone, nav.follow(NotificationTap("chat.message", "/children/c9/chat/t-maya", "nt-1", childId = "c9")), "not her child any more")
        assertEquals(gone, nav.follow(NotificationTap("complaint.message", "/children/c1/complaints/cp-gone", "nt-1", childId = "c1", collapseKey = "complaint:cp-gone")), "complaint gone")
    }

    @Test fun aListRowIsTheSameTapAsItsPush() {
        val row = NotificationView("nt-4", NotificationKind.BROADCAST_POSTED, "Announcement", "Body", "/children/c1/broadcasts?open=bc-ann", lessonId = "bc-ann", createdAt = 1L, childId = "c1")
        assertEquals(NotificationTap("broadcast.posted", "/children/c1/broadcasts?open=bc-ann", "nt-4", "bc-ann", "c1", subjectId = "bc-ann"), NotificationTap.of(row))
        val lesson = NotificationView("nt-8", NotificationKind.HOMEWORK_PUBLISHED, "Homework", null, "/children/c1/map", lessonId = "l1", createdAt = 1L, childId = "c1")
        assertEquals("l1", NotificationTap.of(lesson).idFor("lesson"), "a row names its lesson by lessonId, a push by its collapse key")
    }

    /** Every link shape in B4's contract (ebbab61) is read — and followed even when the kind is one this build lacks. */
    @Test fun everyLinkShapeOfTheContractIsReadAndFollowed() = runTest {
        val shapes = mapOf(
            "/children/c1/chat/t-maya" to LinkShape.CHAT,
            "/children/c1/complaints/cp-1" to LinkShape.COMPLAINT,
            "/children/c1/broadcasts?open=bc-ann" to LinkShape.BROADCAST,
            "/children/c1/announcements?open=a1" to LinkShape.ANNOUNCEMENT,
            "/children/c1/teacher-questions/q1" to LinkShape.TEACHER_QUESTION,
            "/children/c1/map" to LinkShape.MAP,
            "/children/c1/progress" to LinkShape.PROGRESS,
        )
        shapes.forEach { (link, shape) -> assertEquals(shape, linkShape(link), link); assertEquals("c1", childOf(link), link) }
        assertEquals(LinkShape.OTHER, linkShape("/teacher/chat?thread=1"))
        assertEquals("t-maya", staffOf("/children/c1/chat/t-maya"))
        assertEquals("bc-ann", openOf("/children/c1/broadcasts?open=bc-ann"))
        assertEquals("a1", openOf("/children/c1/announcements?open=a1"))

        val expected = mapOf(
            "/children/c1/chat/t-maya" to PushNavigator.Step.Parent(teacherThread.asConversation()),
            "/children/c1/complaints/cp-1" to PushNavigator.Step.Parent(Routes.Complaint("c1", "cp-1")),
            "/children/c1/broadcasts?open=bc-ann" to PushNavigator.Step.Parent(Routes.Broadcasts(focusBroadcast = "bc-ann")),
            "/children/c1/announcements?open=a1" to PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-x")),
            "/children/c1/teacher-questions/q1" to PushNavigator.Step.Parent(Routes.Broadcasts(focusRow = "nt-x")),
            "/children/c1/map" to PushNavigator.Step.Child(null),
            "/children/c1/progress" to PushNavigator.Step.Parent(Routes.Progress()),
        )
        for ((link, step) in expected) {
            val w = World().apply { gate.passed() }
            assertEquals(step, w.navigator(listOf(teacherThread)).follow(NotificationTap("kind.from.later", link, "nt-x", childId = "c1")), link)
        }
    }

    /** M8: B6's push carries `complaintId`; a row names its complaint by `lessonId`; both reach the same page. */
    @Test fun aComplaintPushAndItsRowCarryTheComplaint() = runTest {
        val push = PushPayload.parse(PushMessage(NotificationKind.COMPLAINT_MESSAGE, "Ms Lina replied", "We shortened it.", "nt-5", "c1", "/children/c1/complaints/cp-1", null, "complaint:cp-1", complaintId = "cp-1").toData())!!
        assertEquals("cp-1", push.tap.complaintId)
        assertEquals("complaint:cp-1", push.tag, "a reply and a status change replace each other in the shade")
        val row = NotificationView("nt-5", NotificationKind.COMPLAINT_MESSAGE, "Ms Lina replied", "We shortened it.", "/children/c1/complaints/cp-1", lessonId = "cp-1", createdAt = 1L, childId = "c1")
        for (tap in listOf(push.tap.copy(outside = false), NotificationTap.of(row))) {
            val w = World().apply { gate.passed() }
            assertEquals(PushNavigator.Step.Parent(Routes.Complaint("c1", "cp-1")), w.navigator(emptyList()).follow(tap), tap.toString())
        }
        // A row written before B6 still links the coordinator's thread: it is followed as one.
        val w = World().apply { gate.passed() }
        val old = NotificationTap("complaint.status", "/children/c1/chat/co-lina", "nt-6", childId = "c1", collapseKey = "chat:th-2")
        assertEquals(PushNavigator.Step.Parent(complaintThread.asConversation()), w.navigator(listOf(teacherThread, complaintThread)).follow(old))
    }

    /** M8: this build decodes the row kinds older builds could not — `complaint.message` above all. */
    @Test fun theComplaintKindsDecode() {
        for (kind in listOf("complaint.message", "complaint.status", "complaint.new")) {
            val json = """{"id":"n1","kind":"$kind","title":"t","link":"/children/c1/complaints/cp-1","createdAt":1}"""
            assertEquals(kind, PushMessage.kindName(AppJson.decodeFromString(NotificationView.serializer(), json).kind))
        }
    }

    @Test fun aSignedOutAppFollowsNothing() = runTest {
        val w = World().apply { signedIn = false }
        assertEquals(PushNavigator.Step.Nothing, w.navigator(emptyList()).follow(table.first().first))
    }

    // ---- the lock ----------------------------------------------------------------------------------------------

    @AfterTest fun clearLinks() { PushLinks.consumed(); PushLinks.followed(); PushLinks.abandoned() }

    private class Prompt(var result: BiometricResult) : BiometricAuthenticator {
        var prompts = 0
        override fun kind() = BiometricKind.FINGERPRINT
        override suspend fun authenticate(reason: String): BiometricResult { prompts++; return result }
    }

    private class LockPrefs : BiometricPreferences {
        override suspend fun choice(uid: String) = BiometricChoice.ENABLED
        override suspend fun set(uid: String, choice: BiometricChoice) = Unit
        override suspend fun signedOut() = Unit
    }

    private class NoLockPrefs : BiometricPreferences {
        override suspend fun choice(uid: String) = BiometricChoice.DECLINED
        override suspend fun set(uid: String, choice: BiometricChoice) = Unit
        override suspend fun signedOut() = Unit
    }

    @Test fun aColdStartTapWaitsForTheAppAndTheLock() = runTest {
        // The tap arrives in onCreate, before the shared UI exists: it waits in the flow, it is not lost.
        val tap = table.first().first
        PushLinks.open(tap)
        val prompt = Prompt(BiometricResult.CANCELLED)
        val lock = AppLock(Auth(signedIn = true), LockPrefs(), prompt, signOut = {}, elapsed = { 0L })
        lock.coldStart()
        assertEquals(AppLock.Stage.LOCKED, lock.state.value.stage, "the app starts behind its lock")
        assertEquals(tap, PushLinks.pending.value)
        lock.unlock("Unlock")
        assertEquals(AppLock.Stage.LOCKED, lock.state.value.stage, "a cancelled prompt keeps everything behind the lock")
    }
}
