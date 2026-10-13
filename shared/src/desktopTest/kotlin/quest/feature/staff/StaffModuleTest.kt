package quest.feature.staff

import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import quest.api.AuthProvider
import quest.core.db.Db
import quest.core.platform.DriverFactory
import quest.di.ApiConfig
import quest.di.appModules
import quest.feature.auth.domain.SessionRoles
import quest.feature.chat.data.ChatSocketClient
import quest.feature.push.domain.PushRegistration
import quest.feature.push.presentation.PushNavigator
import quest.feature.staff.data.SessionRolesImpl
import quest.feature.staff.data.StaffAuth
import quest.feature.staff.data.StaffSession
import quest.feature.staff.domain.StaffBadges
import quest.feature.staff.domain.StaffGateway
import quest.feature.staff.domain.StaffSessionView
import quest.feature.staff.domain.StaffThreads
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Feature 13: the graph resolves.
 *
 * Worth its own test for two reasons a compiler cannot catch. The staff session wears `AuthProvider`, so a `get()`
 * that should hand out the **parent's** provider could quietly hand out hers; and the staff socket is a second
 * `ChatSocketClient`, told apart from the parent's only by name — two singletons of one class where a single `get()`
 * would be ambiguous. (The third mix-up, both ends of a conversation being the same view-model class, is kept out by
 * naming the staff definition: Koin allows overrides by default, so an unnamed second one would have silently
 * replaced the parent's and broken her chat with nothing to see at start-up.)
 */
class StaffModuleTest {

    // `ApiConfig.Fake` so nothing reaches the network, and an in-memory database instead of the desktop app's file.
    private val koin = startKoin {
        modules(appModules(ApiConfig.Fake))
        modules(org.koin.dsl.module { single { Db(DriverFactory(null)) } })
    }.koin

    @AfterTest fun tearDown() = stopKoin()

    @Test fun `the staff side of the graph resolves`() {
        koin.get<StaffAuth>()
        koin.get<StaffGateway>()
        koin.get<StaffSession>()
        koin.get<StaffThreads>()
        koin.get<StaffBadges>()
        koin.get<SessionRoles>()
        koin.get<PushRegistration>()
        koin.get<PushNavigator>()
    }

    /** `SessionRoles` is the one object both halves of the sign-in go through, and it is the same one everywhere. */
    @Test fun `the session roles are one object, shared with its implementation`() {
        assertSame(koin.get<SessionRoles>(), koin.get<SessionRolesImpl>())
        assertSame(koin.get<StaffSessionView>(), koin.get<StaffSession>())
    }

    /** Her session is an `AuthProvider` too, so this is the mix-up to keep out: `get<AuthProvider>()` is the parent's. */
    @Test fun `the parent auth provider is not the staff one`() {
        assertNotSame<Any>(koin.get<AuthProvider>(), koin.get<StaffAuth>())
    }

    /** One connection per person: her socket is a second client, named, with her bearer on it. */
    @Test fun `the staff socket is its own client`() {
        val parentSocket = koin.get<ChatSocketClient>()
        val staffSocket = koin.get<ChatSocketClient>(named("staffChatSocket"))
        assertNotSame(parentSocket, staffSocket)
    }
}
