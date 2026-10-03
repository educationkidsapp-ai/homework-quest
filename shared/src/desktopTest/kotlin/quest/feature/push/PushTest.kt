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
import quest.core.navigation.Routes
import quest.feature.broadcasts.domain.BroadcastsRepository
import quest.feature.chat.domain.ChatConnectionState
import quest.feature.chat.domain.ChatRepository
import quest.feature.children.domain.ChildrenRepository
import quest.feature.lock.domain.AppLock
import quest.feature.notifications.domain.NotificationsRepository
import quest.feature.push.domain.FollowPushUseCase
import quest.feature.push.domain.PushChannel
import quest.feature.push.domain.PushLinks
import quest.feature.push.domain.PushOpen
import quest.feature.push.domain.PushPayload
import quest.feature.push.domain.PushPreferences
import quest.feature.push.domain.PushPrompts
import quest.feature.push.domain.PushRegistrar
import quest.feature.push.domain.PushRegistration
import quest.feature.push.domain.PushTarget
import quest.feature.push.domain.PushTokens
import quest.feature.push.domain.pushTarget
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
        override suspend fun unregister(token: String) { calls += "DELETE $token"; devices.remove(token) }
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
        assertEquals(listOf("POST fcm-1", "POST fcm-2", "DELETE fcm-1"), s.server.calls)
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
        assertEquals(listOf("POST fcm-1", "DELETE fcm-1"), s.server.calls)
        assertEquals(1, s.tokens.deleted)
        assertNull(s.prefs.token)
        assertEquals(PushRegistration.Status.OFF, s.registration.status.value)
    }

    @Test fun anExpiredSessionForgetsTheTokenOnTheDeviceOnly() = runTest {
        val s = Setup()
        s.registration.signedIn()
        s.auth.flow.value = AuthState.SignedOut               // Firebase refused the refresh token
        s.registration.sessionExpired()
        assertEquals(listOf("POST fcm-1"), s.server.calls, "no bearer is left to DELETE with")
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
        assertEquals(PushOpen("/children/c1/chat/t-maya", "nt-1"), chat.open)
        assertEquals(PushChannel.EXAM_RESULTS, PushPayload.parse(data(NotificationKind.EXAM_RELEASED, "/children/c1/progress", "lesson:e1"))!!.channel)
        assertEquals(PushChannel.HOMEWORK, PushPayload.parse(data(NotificationKind.HOMEWORK_PUBLISHED, "/children/c1/map", "lesson:l1"))!!.channel)
        val news = PushPayload.parse(data(NotificationKind.BROADCAST_POSTED, "/children/c1/broadcasts?open=b1", "broadcast:b1", id = null, broadcast = "b1"))!!
        assertEquals(PushChannel.SCHOOL_NEWS, news.channel)
        assertEquals(PushOpen("/children/c1/broadcasts?open=b1", null, "b1"), news.open)
    }

    @Test fun aSecondMessageInTheSameThreadReplacesTheFirst() {
        val first = PushPayload.parse(data(NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t-maya", "chat:th-1", body = "One"))!!
        val second = PushPayload.parse(data(NotificationKind.CHAT_MESSAGE, "/children/c1/chat/t-maya", "chat:th-1", body = "Two"))!!
        assertEquals(first.tag, second.tag)
    }

    @Test fun aMapThatIsNotOursShowsNothing() {
        assertNull(PushPayload.parse(mapOf("kind" to "something.else", "title" to "x", "collapseKey" to "k")))
        assertNull(PushPayload.parse(mapOf("kind" to "chat.message", "collapseKey" to "k")), "no title")
        assertNull(PushPayload.parse(emptyMap()))
    }

    @Test fun linksBecomeTargets() {
        assertEquals(PushTarget.Conversation("c1", "t-maya"), pushTarget("/children/c1/chat/t-maya"))
        assertEquals(PushTarget.Progress("c1"), pushTarget("/children/c1/progress"))
        assertEquals(PushTarget.ChildHome("c1"), pushTarget("/children/c1/map"))
        assertEquals(PushTarget.Broadcasts("c1"), pushTarget("/children/c1/broadcasts?open=b1"))
        assertEquals(PushTarget.ParentHome, pushTarget("/teacher/chat?thread=1"))
        assertEquals(PushTarget.ParentHome, pushTarget(null))
    }

    // ---- following a tap -----------------------------------------------------------------------------------------

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
        override suspend fun threads(childId: String) = threads.filter { it.childId == childId }
        override suspend fun coordinators(childId: String): List<ChatThread> = emptyList()
        override suspend fun managers(childId: String): List<ChatThread> = emptyList()
        override suspend fun messages(childId: String, teacherId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun sendMessage(childId: String, teacherId: String, body: String, clientId: String, topic: ChatTopic?): ChatMessage = error("not used")
        override suspend fun markRead(childId: String, teacherId: String) = Unit
        override suspend fun sendTyping(childId: String, teacherId: String) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit
    }

    private class Rows : NotificationsRepository {
        val read = mutableListOf<String>()
        override suspend fun rows(childId: String): List<NotificationView> = emptyList()
        override suspend fun markRead(id: String): NotificationView { read += id; return NotificationView(id, NotificationKind.CHAT_MESSAGE, "t", "b", null, readAt = 1L, createdAt = 0L) }
    }

    private class Feed : BroadcastsRepository {
        val read = mutableListOf<String>()
        override suspend fun feed(childId: String): BroadcastFeed = error("not used")
        override suspend fun plans(childId: String): WeeklyPlanArchive = error("not used")
        override suspend fun markRead(childId: String, broadcastId: String): BroadcastView { read += "$childId/$broadcastId"; error("the row itself is not needed") }
    }

    private val maya = ChatThread(id = "th-1", childId = "c1", childName = "Hala", teacherId = "t-maya", teacherName = "Ms Maya")

    private fun navigator(kids: Kids = Kids(hala, omar), signedIn: Boolean = true, rows: Rows = Rows(), feed: Feed = Feed(), threads: List<ChatThread> = listOf(maya)) =
        PushNavigator(FollowPushUseCase(kids, Chat(threads), rows, feed), signedIn = { signedIn })

    @Test fun aMessageGoesThroughTheGateThenOpensTheThreadForTheRightChild() = runTest {
        val kids = Kids(hala, omar)                           // Omar is the current child
        val rows = Rows()
        val nav = navigator(kids, rows = rows)
        assertEquals(Routes.ParentPin(push = "/children/c1/chat/t-maya"), nav.beforeGate(PushOpen("/children/c1/chat/t-maya", "nt-1")))
        assertEquals("c1", kids.currentChild.value?.id, "the child the push is about is selected")
        assertEquals(listOf("nt-1"), rows.read)
        val conversation = nav.afterGate("/children/c1/chat/t-maya") as Routes.ChatConversation
        assertEquals("t-maya", conversation.teacherId)
        assertEquals("th-1", conversation.threadId)
    }

    @Test fun aResultOpensProgressAndNewsTheFeedBothBehindTheGate() = runTest {
        val feed = Feed()
        val nav = navigator(feed = feed)
        assertEquals(Routes.ParentPin(push = "/children/c1/progress"), nav.beforeGate(PushOpen("/children/c1/progress", "nt-2")))
        assertEquals(Routes.Progress, nav.afterGate("/children/c1/progress"))
        nav.beforeGate(PushOpen("/children/c1/broadcasts?open=b1", null, "b1"))
        assertEquals(listOf("c1/b1"), feed.read)
        assertEquals(Routes.Broadcasts, nav.afterGate("/children/c1/broadcasts?open=b1"))
    }

    @Test fun homeworkOpensTheChildsHomeWithoutTheParentGate() = runTest {
        val kids = Kids(hala, omar)
        assertEquals(Routes.WorldMap, navigator(kids).beforeGate(PushOpen("/children/c1/map", "nt-3")))
        assertEquals("c1", kids.currentChild.value?.id)
    }

    @Test fun staleLinksEndOnTheParentHomeWithoutAnError() = runTest {
        val nav = navigator(threads = emptyList())
        assertNull(nav.afterGate("/children/c1/chat/t-gone"), "the thread is gone: stay on the parent home")
        assertEquals(Routes.ParentPin(), nav.beforeGate(PushOpen("/children/c9/chat/t-maya", "nt-1")), "not her child any more")
        assertEquals(Routes.ParentPin(), nav.beforeGate(PushOpen("/somewhere/else", null)))
    }

    @Test fun aSignedOutAppFollowsNothing() = runTest {
        assertNull(navigator(signedIn = false).beforeGate(PushOpen("/children/c1/chat/t-maya", "nt-1")))
    }

    // ---- cold start through the lock ----------------------------------------------------------------------------

    @AfterTest fun clearLinks() { PushLinks.consumed(); PushLinks.followed() }

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

    @Test fun aColdStartTapWaitsForTheAppAndGoesThroughTheLockAndTheGate() = runTest {
        // The tap arrives in onCreate, before the shared UI exists: it waits, it is not lost.
        PushLinks.open(PushOpen("/children/c1/chat/t-maya", "nt-1"))
        val prompt = Prompt(BiometricResult.SUCCESS)
        val lock = AppLock(Auth(signedIn = true), LockPrefs(), prompt, signOut = {}, elapsed = { 0L })
        lock.coldStart()
        assertEquals(AppLock.Stage.LOCKED, lock.state.value.stage, "the app starts behind its lock")

        val open = PushLinks.pending.value!!
        PushLinks.consumed()
        val nav = navigator()
        // The first leg is the parent gate, never the thread: the navigation happens under the lock's cover.
        assertEquals(Routes.ParentPin(push = "/children/c1/chat/t-maya"), nav.beforeGate(open))
        // The gate asks for the owner only once the app's own lock has been lifted.
        lock.unlock("Unlock")
        assertTrue(lock.confirmOwner("Parent area"))
        assertEquals(2, prompt.prompts)
        PushLinks.unlocked("/children/c1/chat/t-maya")
        assertEquals(Routes.ChatConversation::class, nav.afterGate(PushLinks.afterGate.value!!)!!::class)
    }
}
