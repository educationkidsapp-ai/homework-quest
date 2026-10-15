package quest.core.platform

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.IslandState
import quest.api.samples.HotSoupSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.feature.auth.data.FakeAuth
import quest.feature.content.data.FakeContentApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iOS journey UI test (`QUEST_UI_TEST`) pins [Today] to the Hot Soup seed's day. The fake server's seed lessons have
 * fixed dates, and the child home asks for the last 30 days only (`MapViewModel`), so on the wall clock the lesson left
 * the home on 15 Oct 2026 and the UI test could not find its card.
 */
class TodayPinTest {
    private val auth = FakeAuth(SettingsStore(Db(DriverFactory(null))))
    private val api = FakeContentApi(auth, delayMillis = 0)   // the default `today` is Today.date(), as in the app

    @AfterTest fun unpin() { Today.pinned = null }

    /** The home's request, exactly as `MapViewModel.load` builds it. */
    private suspend fun homeIslands(): List<quest.api.dto.Island> {
        auth.signIn("parent@example.com", "secret123")
        val child = api.createChild(CreateChildRequest("Maya", "sun", Curriculum.BRITISH, 1))
        val today = Today.date()
        return api.map(child.id, today.minus(30, DateTimeUnit.DAY), today.plus(7, DateTimeUnit.DAY)).islands
    }

    @Test fun pinned_to_the_seed_day_the_home_offers_hot_soup_to_play_today() = runTest {
        Today.pinned = HotSoupSeed.date
        assertEquals(HotSoupSeed.date, Today.date())
        val hot = homeIslands().single { it.lessonId == HotSoupSeed.LESSON_ID }
        assertEquals(IslandState.TODAY, hot.state)
    }

    @Test fun thirty_one_days_after_the_seed_the_home_no_longer_carries_it() = runTest {
        Today.pinned = LocalDate(2026, 10, 15)   // the day the unpinned UI test started failing
        assertNull(homeIslands().firstOrNull { it.lessonId == HotSoupSeed.LESSON_ID })
    }
}
