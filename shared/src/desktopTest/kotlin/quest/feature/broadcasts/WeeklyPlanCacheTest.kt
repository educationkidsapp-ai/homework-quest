package quest.feature.broadcasts

import kotlinx.coroutines.test.runTest
import quest.api.ApiException
import quest.api.ContentApi
import quest.api.dto.ApiError
import quest.api.dto.BroadcastKind
import quest.api.dto.BroadcastView
import quest.api.dto.ChatStaffRole
import quest.api.dto.WeeklyPlanArchive
import quest.api.dto.WeeklyPlanEntry
import quest.api.dto.WeeklyPlanWeek
import quest.core.db.Db
import quest.core.platform.DriverFactory
import quest.core.db.SettingsStore
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import quest.feature.broadcasts.data.BroadcastsRepositoryImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MH3: the archive is the only thing the app remembers, and it exists so the **cached image** is reachable — a plan
 * downloaded on the bus has to still be on the screen in the car park, and an image with nothing to list it is an
 * image the parent cannot open.
 *
 * The line this draws is between "the request never reached the server" and "the server answered". A flag turned off
 * must clear the page, not be papered over by what the device remembers.
 */
class WeeklyPlanCacheTest {
    private val settings = SettingsStore(Db(DriverFactory(null)))

    private val plan = BroadcastView(
        id = "p1", kind = BroadcastKind.WEEKLY_PLAN, authorId = "mg", authorName = "Ms. Nour",
        authorRole = ChatStaffRole.MANAGERIAL, weekStart = "2026-09-27", grade = 1, bodyEn = "Plan",
        createdAt = 1_759_000_000_000L,
    )
    private val archive = WeeklyPlanArchive(
        "2026-09-20", "2026-09-27", unread = 1,
        weeks = listOf(WeeklyPlanWeek("2026-09-27", listOf(WeeklyPlanEntry(plan)))),
    )

    /** Only the archive matters here; everything else on the contract is the fake's, by delegation. */
    private class Api(base: ContentApi, var answer: () -> WeeklyPlanArchive) : ContentApi by base {
        var calls = 0
        override suspend fun childWeeklyPlans(childId: String, from: String?, to: String?): WeeklyPlanArchive {
            calls++
            return answer()
        }
    }

    private fun api(answer: () -> WeeklyPlanArchive) = Api(FakeContentApi(FakeAuth(settings), delayMillis = 0), answer)

    private fun repo(api: ContentApi) = BroadcastsRepositoryImpl(api, settings)

    @Test fun theLastArchiveIsAnsweredWhenTheRequestCannotBeMade() = runTest {
        settings.load()
        val api = api { archive }
        assertEquals(1, repo(api).plans("c1").weeks.size)

        api.answer = { throw ApiException(ApiError(ApiError.NETWORK, "offline")) }
        val offline = repo(api).plans("c1")
        assertEquals("2026-09-27", offline.weeks.single().weekStart)
        assertEquals("p1", offline.weeks.single().items.single().plan.id)
    }

    /** Another child's cache is not hers: the key carries the child id. */
    @Test fun theCacheIsPerChild() = runTest {
        settings.load()
        val api = api { archive }
        repo(api).plans("c1")
        api.answer = { throw ApiException(ApiError(ApiError.NETWORK, "offline")) }
        assertFailsWith<ApiException> { repo(api).plans("c2") }
    }

    /**
     * The flag going off is an answer, not a failure — 404 has to reach the page so it shows "not enabled" instead of
     * a plan the school has withdrawn.
     */
    @Test fun anAnswerFromTheServerIsNeverPaperedOver() = runTest {
        settings.load()
        val api = api { archive }
        repo(api).plans("c1")
        api.answer = { throw ApiException(ApiError(ApiError.NOT_FOUND, "off")) }
        val thrown = assertFailsWith<ApiException> { repo(api).plans("c1") }
        assertEquals(ApiError.NOT_FOUND, thrown.error.code)
    }

    @Test fun nothingCachedYetRethrows() = runTest {
        settings.load()
        val api = api { throw ApiException(ApiError(ApiError.NETWORK, "offline")) }
        assertFailsWith<ApiException> { repo(api).plans("fresh") }
        assertTrue(api.calls == 1)
    }
}
