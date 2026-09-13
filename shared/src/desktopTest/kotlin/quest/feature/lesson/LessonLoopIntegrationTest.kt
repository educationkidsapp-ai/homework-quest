package quest.feature.lesson

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import quest.api.dto.GenerateMode
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.core.db.Db
import quest.core.db.QuestionSetDao
import quest.core.db.SettingsStore
import quest.core.platform.DriverFactory
import quest.feature.lesson.data.FakeLessonApi
import quest.feature.lesson.data.LessonRepositoryImpl
import quest.feature.lesson.domain.NewLesson
import quest.feature.lesson.domain.SkillDecision
import quest.feature.map.domain.IslandStatus
import quest.feature.map.domain.IslandsUseCase
import quest.feature.parent.domain.RequeueWeakSkillsUseCase
import quest.feature.practice.data.PracticeRepositoryImpl
import quest.feature.practice.domain.Band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The whole loop against the fake API and an in-memory SQLite database: upload → confirm → play → again → requeue. */
class LessonLoopIntegrationTest {
    private val db = Db(DriverFactory(null))
    private val dao = QuestionSetDao(db)
    private val settings = SettingsStore(db)
    private val childId: suspend () -> String = { "child" }
    private val api = FakeLessonApi(timings = FakeLessonApi.Timings(10, 10, 10, 10))
    private val lessons = LessonRepositoryImpl(api, db, dao, settings, childId)
    private val practice = PracticeRepositoryImpl(db, dao, childId)
    private val today = LocalDate(2026, 9, 14)

    @Test fun uploadConfirmPlayAgain() = runTest {
        val job = lessons.create(NewLesson(Subject.MATH, today, emptyList(), typedTask = null))
        assertEquals(LessonStatus.UPLOADING, job.status)

        val extracted = lessons.observe(job.id).first { it.status.isTerminal }
        assertEquals(LessonStatus.NEEDS_CONFIRMATION, extracted.status)
        assertEquals(2, extracted.skills.size)
        assertTrue(extracted.skills[1].isUnsure)

        // Parent keeps counting, resolves the unsure one to its second candidate, adds a manual skill.
        val decisions = listOf(
            SkillDecision(extracted.skills[0].id, extracted.skills[0].name, Subject.MATH, extracted.skills[0].method, keep = true),
            SkillDecision(extracted.skills[1].id, "Adding two numbers", Subject.MATH, extracted.skills[1].method, keep = true),
            SkillDecision(null, "Counting by 10s", Subject.MATH, null, keep = true),
        )
        lessons.confirm(job.id, decisions)
        val ready = lessons.observe(job.id).first { it.status.isTerminal }
        assertEquals(LessonStatus.READY, ready.status)
        assertEquals(3, ready.questionSets.size)

        val skills = lessons.confirmedSkillsFor(today)
        assertEquals(3, skills.size)
        assertEquals("Adding two numbers", skills.first { it.id == extracted.skills[1].id }.name)

        // Files are deleted automatically once ready.
        assertTrue(lessons.lesson(job.id)!!.filesDeleted)

        // Local question set stored; play works from the DB.
        val counting = skills.first { it.name == "Counting by 2s" }
        val set = lessons.latestSet(counting.id)
        assertNotNull(set)
        assertEquals(7, set.questions.size)
        assertEquals("Counting by 2s means we jump two each time!", set.explanation)

        // "Again" never repeats a shown question.
        val again = lessons.generate(counting.id, GenerateMode.AGAIN)
        assertEquals(7, again.questions.size)
        val shown = set.questions.map { it.id }.toSet()
        assertTrue(again.questions.none { it.id in shown })
        val harder = lessons.generate(counting.id, GenerateMode.HARDER)
        assertTrue(harder.questions.none { it.id in shown + again.questions.map { q -> q.id } })

        // Map: the three skills are today's islands.
        val map = IslandsUseCase(lessons, practice)(today)
        assertEquals(3, map.islands.count { it.status == IslandStatus.TODAY })
    }

    @Test fun weakSkillIsRequeuedForTomorrow() = runTest {
        val job = lessons.create(NewLesson(Subject.ENGLISH, today, emptyList(), null))
        val extracted = lessons.observe(job.id).first { it.status.isTerminal }
        lessons.confirm(job.id, extracted.skills.map { SkillDecision(it.id, it.name, it.subject, it.method, true) })
        lessons.observe(job.id).first { it.status.isTerminal }
        val skill = lessons.confirmedSkillsFor(today).first()
        val set = lessons.latestSet(skill.id)!!

        // 7 first tries, only 2 right → below 60 %.
        set.questions.forEachIndexed { i, q -> practice.recordAttempt(q.id, skill.id, "x", i < 2, 1) }
        assertEquals(Band.NEEDS_ANOTHER_LOOK, practice.progress(skill.id).band)

        val requeued = RequeueWeakSkillsUseCase(lessons, practice)(today)
        assertEquals(listOf(skill.id), requeued)
        val tomorrow = LocalDate(2026, 9, 15)
        assertTrue(lessons.confirmedSkillsFor(tomorrow).any { it.id == skill.id })
    }

    @Test fun errorStateIsSurfaced() = runTest {
        val job = lessons.create(NewLesson(Subject.MATH, today, emptyList(), typedTask = "error"))
        val terminal = lessons.observe(job.id).first { it.status.isTerminal }
        assertEquals(LessonStatus.ERROR, terminal.status)
        assertEquals("unreadable_file", lessons.lesson(job.id)!!.error!!.code)
    }

    @Test fun practiceLengthIsPassedToTheGenerator() = runTest {
        settings.setPracticeLength(5)
        val job = lessons.create(NewLesson(Subject.MATH, today, emptyList(), typedTask = "Count by 5s"))
        val extracted = lessons.observe(job.id).first { it.status.isTerminal }
        lessons.confirm(job.id, extracted.skills.map { SkillDecision(it.id, it.name, it.subject, it.method, true) })
        val ready = lessons.observe(job.id).first { it.status.isTerminal }
        assertEquals(5, ready.questionSets.single().questions.size)
    }
}
