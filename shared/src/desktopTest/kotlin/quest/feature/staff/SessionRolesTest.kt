package quest.feature.staff

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import quest.api.ApiException
import quest.api.AuthProvider
import quest.api.AuthState
import quest.api.dto.ApiError
import quest.api.dto.AttachmentRef
import quest.api.dto.ChatMessage
import quest.api.dto.ChatReadReceipt
import quest.api.dto.ChatThread
import quest.api.dto.NotificationView
import quest.api.dto.RegisterDeviceRequest
import quest.api.dto.SendChatMessageRequest
import quest.api.dto.UnreadCount
import quest.api.staff.StaffMe
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.chat.domain.StagedUpload
import quest.feature.staff.data.SessionRolesImpl
import quest.feature.staff.data.StaffAuth
import quest.feature.staff.data.StaffSession
import quest.feature.staff.domain.RoleRouter
import quest.feature.staff.domain.SessionSide
import quest.feature.staff.domain.StaffGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 13: one screen, two sign-ins. The credentials are offered to `/auth/sign-in` and to Firebase, and the
 * sides that took them are what the app routes by — so the cases that matter are staff only, parent only, both,
 * neither, and "the staff side signed in but her school does not have the staff app".
 */
class SessionRolesTest {

    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)

    private val staffOk = """{"token":"t-1","email":"w.ahmed.4821","expiresAt":4102444800000,"role":"WORKER","schoolId":"s-1","displayName":"Ahmed","mustChangePassword":false,"refreshToken":"r-1"}"""
    private val staffTemporary = """{"token":"t-1","email":"t@x.com","expiresAt":4102444800000,"role":"TEACHER","schoolId":"s-1","displayName":"Noor","mustChangePassword":true,"refreshToken":"r-1"}"""

    /** Firebase's half, scripted: either it takes the credentials or it refuses them. */
    private class FakeParentAuth(private val accepts: Boolean) : AuthProvider {
        private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
        override val state: StateFlow<AuthState> = _state
        var signedOut = 0
        override suspend fun signIn(email: String, password: String) {
            if (!accepts) throw IllegalStateException("Wrong email or password.")
            _state.value = AuthState.SignedIn("uid", email)
        }
        override suspend fun signOut() { signedOut++; _state.value = AuthState.SignedOut }
        override suspend fun idToken(forceRefresh: Boolean): String? = (state.value as? AuthState.SignedIn)?.let { "parent-token" }
    }

    /** `GET /staff/me`: the body, or the refusal a school with the flag off answers with. */
    private class FakeGateway(private val me: StaffMe?) : StaffGateway {
        var meCalls = 0
        override suspend fun staffMe(): StaffMe {
            meCalls++
            return me ?: throw ApiException(ApiError(ApiError.NOT_FOUND, "No such endpoint."))
        }
        override suspend fun staffThreads(): List<ChatThread> = emptyList()
        override suspend fun staffMessages(threadId: String, before: String?, since: String?, limit: Int?): List<ChatMessage> = emptyList()
        override suspend fun staffSend(threadId: String, request: SendChatMessageRequest): ChatMessage = error("not used")
        override suspend fun staffRead(threadId: String): ChatReadReceipt = error("not used")
        override suspend fun uploadAttachment(file: StagedUpload, onProgress: (Float) -> Unit): AttachmentRef = error("not used")
        override suspend fun notifications(limit: Int): List<NotificationView> = emptyList()
        override suspend fun unreadNotificationCount(): UnreadCount = UnreadCount(0)
        override suspend fun markNotificationRead(id: String): NotificationView = error("not used")
        override suspend fun markAllNotificationsRead(): UnreadCount = UnreadCount(0)
        override suspend fun registerDevice(request: RegisterDeviceRequest) = Unit
        override suspend fun unregisterDevice(token: String) = Unit
    }

    private fun staffAuth(script: (HttpRequestData) -> Pair<HttpStatusCode, String>) = StaffAuth(
        baseUrl = "https://api.test", settings = settings,
        engineClient = HttpClient(MockEngine { req ->
            val (status, body) = script(req)
            respond(body, status, headersOf("Content-Type", "application/json"))
        }),
    )

    private fun me(role: String = "TEACHER", flags: Map<String, Boolean> = mapOf("staffMobileApp" to true)) =
        StaffMe("u-1", "Noor", role, null, "s-1", "Learning International School", flags)

    private fun roles(
        staffAccepts: Boolean,
        parentAccepts: Boolean,
        gateway: FakeGateway = FakeGateway(me()),
        body: String = staffOk,
    ): Triple<SessionRolesImpl, FakeParentAuth, StaffAuth> {
        val auth = staffAuth { if (staffAccepts) HttpStatusCode.OK to body else HttpStatusCode.Unauthorized to """{"code":"unauthorized","message":"Wrong email or password."}""" }
        val parent = FakeParentAuth(parentAccepts)
        val session = StaffSession(gateway)
        return Triple(SessionRolesImpl(auth, parent, session, settings), parent, auth)
    }

    @Test fun `a worker signs in on the staff side alone`() = runTest {
        settings.load()
        val (roles, _, auth) = roles(staffAccepts = true, parentAccepts = false)
        val result = roles.signIn("w.ahmed.4821", "temp-pass")
        assertEquals(setOf(SessionSide.STAFF), result.sides)
        assertNull(result.error)
        assertTrue(auth.signedIn)
        assertEquals("t-1", auth.idToken(), "the access token is attached without a refresh")
        assertEquals(SessionSide.STAFF, roles.side.value, "a fresh sign-in opens the staff side")
    }

    @Test fun `a parent signs in on the parent side alone`() = runTest {
        settings.load()
        val (roles, parent, auth) = roles(staffAccepts = false, parentAccepts = true)
        val result = roles.signIn("p@x.com", "secret1")
        assertEquals(setOf(SessionSide.PARENT), result.sides)
        assertFalse(auth.signedIn)
        assertTrue(parent.state.value is AuthState.SignedIn)
        assertEquals(SessionSide.PARENT, roles.side.value)
    }

    @Test fun `a teacher whose child is at the school holds both sides`() = runTest {
        settings.load()
        val (roles, _, _) = roles(staffAccepts = true, parentAccepts = true)
        val result = roles.signIn("t@x.com", "secret1")
        assertEquals(setOf(SessionSide.STAFF, SessionSide.PARENT), result.sides)
        assertEquals(SessionSide.STAFF, roles.side.value)
        roles.choose(SessionSide.PARENT)
        assertEquals(SessionSide.PARENT, roles.side.value)
        assertEquals("parent", settings.get(SettingsStore.KEY_SESSION_SIDE), "the choice outlives the launch")
    }

    @Test fun `neither side takes the credentials`() = runTest {
        settings.load()
        val (roles, _, _) = roles(staffAccepts = false, parentAccepts = false)
        val result = roles.signIn("nobody@x.com", "secret1")
        assertTrue(result.sides.isEmpty())
        assertNotNull(result.error)
        assertFalse(result.staffDisabled)
        assertNull(roles.side.value)
    }

    /**
     * Her password is right, but `GET /staff/me` is hidden because her school has `staffMobileApp` off. There is no
     * staff side to show, so the session is dropped again rather than left holding a token for tabs that would 404.
     */
    @Test fun `the flag being off leaves no staff side`() = runTest {
        settings.load()
        val gateway = FakeGateway(me = null)
        val (roles, _, auth) = roles(staffAccepts = true, parentAccepts = false, gateway = gateway)
        val result = roles.signIn("t@x.com", "secret1")
        assertTrue(result.sides.isEmpty())
        assertTrue(result.staffDisabled)
        assertNull(result.error, "a school without the staff app is not an error to show")
        assertFalse(auth.signedIn, "the staff token is given up when there is nothing to use it for")
        assertEquals(1, gateway.meCalls)
    }

    /** With the flag off and a parent side beside it, the app simply opens the parent one. */
    @Test fun `the flag being off still opens the parent side`() = runTest {
        settings.load()
        val (roles, _, _) = roles(staffAccepts = true, parentAccepts = true, gateway = FakeGateway(me = null))
        val result = roles.signIn("t@x.com", "secret1")
        assertEquals(setOf(SessionSide.PARENT), result.sides)
        assertTrue(result.staffDisabled)
        assertEquals(SessionSide.PARENT, roles.side.value)
    }

    @Test fun `a temporary password asks to be replaced`() = runTest {
        settings.load()
        val (roles, _, _) = roles(staffAccepts = true, parentAccepts = false, body = staffTemporary)
        val result = roles.signIn("t@x.com", "temp-pass")
        assertEquals(setOf(SessionSide.STAFF), result.sides)
        assertTrue(result.mustChangePassword)
    }

    /**
     * She was killed on the change-password screen. The flag is kept with the session, so the restored app asks again
     * instead of opening the staff home with the temporary password still in place.
     */
    @Test fun `a restored session still has to replace a temporary password`() = runTest {
        settings.load()
        val (roles, _, auth) = roles(staffAccepts = true, parentAccepts = false, body = staffTemporary)
        roles.signIn("t@x.com", "temp-pass")
        assertEquals("1", settings.get(SettingsStore.KEY_STAFF_MUST_CHANGE))

        auth.restore()
        roles.restored()
        assertEquals(setOf(SessionSide.STAFF), roles.sides.value)
        assertTrue(roles.mustChangeStaffPassword.value, "the step comes back after the app was killed")
        assertEquals(
            RoleRouter.Start.StaffPassword,
            RoleRouter.start(roles.sides.value, roles.side.value, roles.mustChangeStaffPassword.value),
        )
    }

    /** A password that was never temporary leaves nothing behind, and the restore opens her home. */
    @Test fun `a restored session with a real password opens the staff home`() = runTest {
        settings.load()
        val (roles, _, auth) = roles(staffAccepts = true, parentAccepts = false)
        roles.signIn("w.ahmed.4821", "secret1")
        assertNull(settings.get(SettingsStore.KEY_STAFF_MUST_CHANGE))
        auth.restore()
        roles.restored()
        assertFalse(roles.mustChangeStaffPassword.value)
        assertEquals(
            RoleRouter.Start.StaffHome,
            RoleRouter.start(roles.sides.value, roles.side.value, roles.mustChangeStaffPassword.value),
        )
    }

    @Test fun `signing out forgets both sides and the remembered choice`() = runTest {
        settings.load()
        val (roles, parent, auth) = roles(staffAccepts = true, parentAccepts = true)
        roles.signIn("t@x.com", "secret1")
        roles.signOut()
        assertTrue(roles.sides.value.isEmpty())
        assertNull(roles.side.value)
        assertFalse(auth.signedIn)
        assertNull(settings.get(SettingsStore.KEY_SESSION_SIDE))
        assertNull(settings.get(SettingsStore.KEY_STAFF_SESSION))
        assertNull(settings.get(SettingsStore.KEY_STAFF_MUST_CHANGE))
        // The parent half goes through today's `SignOutUseCase`, which this test stands in for.
        assertEquals(0, parent.signedOut)
    }

    @Test fun `a restored staff session comes back with its side`() = runTest {
        settings.load()
        val (roles, _, auth) = roles(staffAccepts = true, parentAccepts = false)
        roles.signIn("w.ahmed.4821", "temp-pass")
        assertEquals("r-1|w.ahmed.4821", settings.get(SettingsStore.KEY_STAFF_SESSION))
        auth.restore()
        roles.restored()
        assertEquals(setOf(SessionSide.STAFF), roles.sides.value)
        assertEquals(SessionSide.STAFF, roles.side.value)
    }
}
