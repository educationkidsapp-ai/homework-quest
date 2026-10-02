package quest.feature.journey

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import quest.api.ContentApi
import quest.api.dto.CreateChildRequest
import quest.api.dto.Curriculum
import quest.api.dto.PublishedLesson
import quest.api.samples.HotSoupSeed
import quest.core.db.Db
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.auth.data.FakeAuth
import quest.feature.children.data.ChildrenRepositoryImpl
import quest.feature.content.data.FakeContentApi
import quest.feature.content.data.JourneyRepositoryImpl
import quest.feature.content.data.LessonRepositoryImpl
import quest.feature.journey.presentation.JourneyContract
import quest.feature.journey.presentation.JourneyViewModel
import quest.feature.journey.presentation.LessonCopy
import quest.feature.journey.presentation.LessonStrings
import quest.feature.journey.presentation.PlayerContract
import quest.feature.journey.presentation.StopPlayerViewModel
import quest.feature.journey.presentation.isExam
import quest.feature.journey.presentation.playFor
import quest.feature.parent.data.ParentRepositoryImpl
import quest.feature.school.data.SchoolSessionImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §8 on the student's side: an exam is the one play the lesson names (`examPlay`), without a level chooser and — with
 * `hintsOff` — without a hint after a wrong answer. Scoring is untouched: the wrong attempt is recorded either way.
 */
class ExamModeTest {
    private val db = Db(DriverFactory(null))
    private val settings = SettingsStore(db)
    private val auth = FakeAuth(settings)

    private val homework = HotSoupSeed.lesson
    private val exam = homework.copy(type = "exam", hintsOff = true, numbersOff = true, examPlay = homework.plays[1])

    private class Serving(base: ContentApi, private val lesson: PublishedLesson) : ContentApi by base {
        override suspend fun lesson(id: String, version: Int?): PublishedLesson = lesson
    }

    private class Rig(val journey: JourneyViewModel, val player: StopPlayerViewModel)

    private suspend fun rig(lesson: PublishedLesson, index: Int = 0): Rig {
        auth.signIn("parent@example.com", "secret123")
        val fake = FakeContentApi(auth, delayMillis = 0)
        val api = Serving(fake, lesson)
        val children = ChildrenRepositoryImpl(api, db, settings, auth)
        val child = fake.createChild(CreateChildRequest("Maya", "sun", Curriculum.BRITISH, 1))
        children.refresh(); children.select(child.id)
        val lessons = LessonRepositoryImpl(api, db)
        val journey = JourneyRepositoryImpl(api, db)
        val copy = LessonCopy(ParentRepositoryImpl(settings), SchoolSessionImpl(fake, fake, settings))
        return Rig(JourneyViewModel(lesson.id, 1, 0, lessons, journey, children, copy), StopPlayerViewModel(lesson.id, 1, 0, index, lessons, journey, children, api, copy))
    }

    private suspend fun <S> settle(read: () -> S, predicate: (S) -> Boolean) {
        // Ten seconds, not two: the fake sign-in alone waits 600 ms of real time and a loaded CI runner is slow to load.
        repeat(2000) { if (predicate(read())) return; delay(5) }
        error("state never settled: ${read()}")
    }

    @Test fun anExamPlaysItsOwnPlayWhateverTheRouteSays() {
        assertTrue(exam.isExam)
        assertEquals(homework.plays[1], exam.playFor(level = 1, variant = 0))
        assertEquals(homework.plays[1], exam.playFor(level = 3, variant = 1))
        assertFalse(homework.isExam)
        assertEquals(homework.plays[0], homework.playFor(1, 0))
        assertEquals(homework.variant, homework.playFor(1, 1))
        // A lesson typed "exam" that carries no play is not sat as one: there is nothing to sit.
        assertFalse(homework.copy(type = "exam").isExam)
    }

    @Test fun theOverviewOfAnExamHasNoLevelToChoose() = runBlocking {
        val rig = rig(exam)
        settle({ rig.journey.state.value }) { !it.loading }
        assertTrue(rig.journey.state.value.exam)
        assertEquals(exam.examPlay, rig.journey.state.value.play)

        rig.journey.dispatch(JourneyContract.Intent.SelectLevel(1))
        delay(50)
        assertEquals(exam.examPlay, rig.journey.state.value.play, "choosing a level does nothing in an exam")
    }

    @Test fun aWrongAnswerInAnExamShowsNoHint() = runBlocking {
        val rig = rig(exam)
        settle({ rig.player.state.value }) { it.phase == PlayerContract.Phase.STOP }
        assertTrue(rig.player.state.value.exam)

        rig.player.dispatch(PlayerContract.Intent.Wrong(attempt = 1, hint = "Look at page 1.", numberLine = null, answer = "x"))
        delay(100)
        assertEquals(PlayerContract.Phase.STOP, rig.player.state.value.phase)
        assertEquals("", rig.player.state.value.hint)
    }

    @Test fun aWrongAnswerInAHomeworkStillOpensTheHint() = runBlocking {
        val rig = rig(homework)
        settle({ rig.player.state.value }) { it.phase == PlayerContract.Phase.STOP }
        assertFalse(rig.player.state.value.exam)

        rig.player.dispatch(PlayerContract.Intent.Wrong(attempt = 1, hint = "Look at page 1.", numberLine = null, answer = "x"))
        settle({ rig.player.state.value }) { it.phase == PlayerContract.Phase.HINT }
        assertEquals("Look at page 1.", rig.player.state.value.hint)
    }

    @Test fun theLessonCopyIsEnglishUntilTheSchoolHasArabicAndTheParentChoseIt() {
        assertEquals("Start lesson", LessonStrings.forLanguage("en").startLesson)
        assertEquals(LessonStrings.ar, LessonStrings.forLanguage("ar"))
        assertEquals(LessonStrings.en, LessonStrings.forLanguage("fr"), "an unknown language is English, never a crash")
        // Every Arabic string exists: none of them is the English default left behind.
        assertTrue(LessonStrings.ar.praises.none { it in LessonStrings.en.praises })
        assertTrue(LessonStrings.ar.stops.check != LessonStrings.en.stops.check)
        assertTrue(LessonStrings.ar.journey.levelNames.values.none { it in LessonStrings.en.journey.levelNames.values })
    }
}
