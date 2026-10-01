package quest.feature.children

import kotlinx.coroutines.test.runTest
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.content.data.FakeContentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M1: the admin adds children and links them to the parent; the app only lists what `GET /children` answers. These
 * are the three things that follow from "the server is the truth": every linked child appears, an unlinked one leaves
 * the device, and an unreachable server changes nothing.
 */
class LinkedChildrenTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth = FakeAuth(settings)

    private val noor = Child("ch-1", "Noor", "sky", Curriculum.BRITISH, 1, schoolId = "s1")
    private val adam = Child("ch-2", "Adam", "mint", Curriculum.BRITISH, 4, schoolId = "s1")

    /** A server whose answer the test controls; null means it cannot be reached. */
    private class Linked(base: ContentApi, var answer: List<Child>?) : ContentApi by base {
        override suspend fun listChildren(): List<Child> = answer ?: error("offline")
    }

    private fun server(answer: List<Child>?) = Linked(FakeContentApi(auth, delayMillis = 0), answer)

    @Test fun everyChildTheAdminLinkedAppearsAfterSignIn() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val repo = ChildrenRepositoryImpl(server(listOf(noor, adam)), db, settings, auth)
        assertEquals(listOf("Noor", "Adam"), repo.refresh().map { it.name })
        assertEquals("ch-1", repo.currentChild.value?.id, "the first one is current until the parent picks")
        assertEquals("s1", repo.currentChild.value?.schoolId)
    }

    @Test fun aParentWithNoLinkedChildHasNoCurrentChild() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val repo = ChildrenRepositoryImpl(server(emptyList()), db, settings, auth)
        assertTrue(repo.refresh().isEmpty())
        assertNull(repo.currentChild.value)
    }

    @Test fun aChildTheAdminUnlinkedLeavesTheDevice() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val api = server(listOf(noor, adam))
        val repo = ChildrenRepositoryImpl(api, db, settings, auth)
        repo.refresh()
        repo.select("ch-2")

        api.answer = listOf(noor)
        assertEquals(listOf("Noor"), repo.refresh().map { it.name })
        assertEquals(listOf("Noor"), repo.children().map { it.name }, "the cached row is gone too")
        assertEquals("ch-1", repo.currentChild.value?.id, "and the selection falls back to a child who is still linked")
    }

    @Test fun anUnreachableServerKeepsTheChildrenTheDeviceKnows() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val api = server(listOf(noor, adam))
        val repo = ChildrenRepositoryImpl(api, db, settings, auth)
        repo.refresh()

        api.answer = null
        assertEquals(setOf("Noor", "Adam"), repo.refresh().map { it.name }.toSet())
    }

    @Test fun withoutABackendTheFakePlaysTheAdmin() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val repo = ChildrenRepositoryImpl(FakeContentApi(auth, delayMillis = 0), db, settings, auth)
        assertEquals(FakeContentApi.linkedChildren.map { it.name }, repo.refresh().map { it.name })
    }
}
