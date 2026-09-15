package quest.feature.content

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import quest.api.AuthProvider
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Stop
import quest.api.dto.StopCategory
import quest.api.samples.HotSoupSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.data.LessonRepositoryImpl
import quest.feature.content.data.MapRepositoryImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Sign in → add child → map → play Level 1 → completion, unlock, review, upload — against the fake API and an in-memory DB. */
class JourneyFlowTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth: AuthProvider = FakeAuth(settings)
    private val api = FakeContentApi(auth, delayMillis = 0, today = { today })   // the seed lesson is dated 2026-09-14; never the wall clock
    private val children = ChildrenRepositoryImpl(api, db, settings, auth)
    private val lessons = LessonRepositoryImpl(api, db)
    private val journey = JourneyRepositoryImpl(api, db)
    private val maps = MapRepositoryImpl(api, lessons, journey)
    private val today = LocalDate(2026, 9, 14)

    @Test fun fullLevelOneFlow() = runTest {
        auth.signIn("parent@example.com", "secret123")
        val child = children.create(CreateChildRequest("Maya", "sun", Curriculum.BRITISH, 1)); children.select(child.id)

        val map = maps.map(child, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        val hot = map.islands.first { it.lessonId == HotSoupSeed.LESSON_ID }
        assertEquals(IslandState.TODAY, hot.state); assertEquals(listOf(1), hot.levelsUnlocked)
        assertEquals(1, map.islands.count { it.kind == IslandKind.LOCKED })

        // the lesson is now cached for offline play
        assertNotNull(lessons.cached(HotSoupSeed.LESSON_ID))

        val lesson = lessons.lesson(HotSoupSeed.LESSON_ID)
        val play = lesson.plays[0]
        play.stops.forEach { stop ->
            val stars = if (stop.category == StopCategory.SINGLE) 3 else 3
            journey.recordStop(child.id, lesson, play, stop.id, stars, "x", true, 1, 0, drawing = if (stop is Stop.ReadPage) "[]" else null)
        }
        val progress = journey.completeLevel(child.id, lesson, play)
        assertEquals(play.stops.size * 3, progress.starsFor(play))
        assertEquals(1, journey.completions(child.id).size)
        assertTrue(journey.media(child.id, lesson.id).isNotEmpty())

        // uploaded, then the server-side map agrees and Level 2 unlocks
        assertEquals(play.stops.size, journey.flushAttempts(child.id))
        val after = maps.map(child, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        val done = after.islands.first { it.lessonId == HotSoupSeed.LESSON_ID }
        assertEquals(IslandState.DONE, done.state); assertEquals(listOf(1, 2), done.levelsUnlocked)
        assertEquals(0, journey.flushAttempts(child.id))
    }

    @Test fun weakSkillProducesAReviewIsland() = runTest {
        auth.signIn("p@example.com", "secret123")
        val child = children.create(CreateChildRequest("Omar", "mint", Curriculum.BRITISH, 1)); children.select(child.id)
        val lesson = lessons.lesson(quest.api.samples.PhonicsSeed.LESSON_ID)
        val play = lesson.plays[0]
        // every single-answer stop wrong on the first try → below 60 %
        play.stops.forEach { s -> if (s.category == StopCategory.SINGLE) { journey.recordWrongAttempt(child.id, lesson, play, s.id, "x", 1); journey.recordStop(child.id, lesson, play, s.id, 2, "y", true, 2, 0) } else journey.recordStop(child.id, lesson, play, s.id, 3, "", true, 1, 0) }
        journey.flushAttempts(child.id)
        val map = maps.map(child, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30), today)
        val review = map.islands.filter { it.kind == IslandKind.REVIEW }
        assertEquals(1, review.size); assertEquals(IslandKind.REVIEW, map.islands.first().kind)
        assertEquals("The sh sound", review.first().title)
    }
}
