package quest.feature.evaluations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.dto.Child
import quest.api.dto.Curriculum
import quest.api.dto.EvaluationCriterion
import quest.api.dto.ParentEvaluation
import quest.api.dto.TeamLatestEvaluation
import quest.feature.children.domain.ChildrenRepository
import quest.feature.evaluations.domain.ChildEvaluationsRepository
import quest.feature.evaluations.domain.EvaluationCriterionKey
import quest.feature.evaluations.domain.orderedCriteria
import quest.feature.evaluations.domain.rounds
import quest.feature.evaluations.presentation.ChildEvaluationsContract
import quest.feature.evaluations.presentation.ChildEvaluationsViewModel
import quest.feature.evaluations.presentation.roundName
import quest.feature.parent.presentation.Strings
import quest.feature.team.presentation.latestLine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feature 33 on the app side: the page groups by round and then reads the server's own order, every criterion is a
 * **word** and never a number, and the answers that are not failures are told apart from the one that is.
 */
class ChildEvaluationsViewModelTest {

    private val maya = Child("c1", "Maya", "sky", Curriculum.BRITISH, 1)

    private class Kids(child: Child?) : ChildrenRepository {
        override val currentChild: StateFlow<Child?> = MutableStateFlow(child)
        override suspend fun refresh(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun children(): List<Child> = listOfNotNull(currentChild.value)
        override suspend fun select(id: String) {}
        override suspend fun clear() {}
    }

    private class Evaluations(private val answer: () -> List<ParentEvaluation>) : ChildEvaluationsRepository {
        var asked: String? = null
        override suspend fun evaluations(childId: String): List<ParentEvaluation> {
            asked = childId
            return answer()
        }
    }

    private val built = mutableListOf<ViewModel>()

    @BeforeTest fun setUp() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest fun tearDown() {
        built.forEach { it.viewModelScope.cancel() }
        built.clear()
        Dispatchers.resetMain()
    }

    private fun viewModel(kids: ChildrenRepository, rows: ChildEvaluationsRepository) =
        ChildEvaluationsViewModel(kids, rows).also { built += it }

    private suspend fun settle(state: StateFlow<ChildEvaluationsContract.State>): ChildEvaluationsContract.State {
        repeat(400) { if (!state.value.loading) return state.value; delay(5) }
        error("the evaluations page never finished loading; last was ${state.value}")
    }

    private fun evaluation(
        round: String,
        subject: String,
        teacher: String,
        criteria: List<EvaluationCriterion> = allFour,
        comment: String? = "She is doing well.",
    ) = ParentEvaluation(
        roundId = round, roundName = "Term 1 — $round", roundNameAr = "الفصل الأول — $round",
        subject = subject, teacherId = "t-$teacher", teacherName = teacher,
        criteria = criteria, comment = comment, goingWell = "Reading aloud", nextStep = "Full sentences",
        releasedAt = "2026-10-18T09:00:00Z",
    )

    private val allFour = listOf(
        EvaluationCriterion("participation", "excellent"),
        EvaluationCriterion("effort", "good"),
        EvaluationCriterion("behaviour", "excellent"),
        EvaluationCriterion("understanding", "developing"),
    )

    // ---- 1. the page: one section per round, newest first, the server's order inside it

    @Test fun thePageIsOneSectionPerRoundNewestFirstAndTheServersOrderWithinIt() = runBlocking {
        val answer = listOf(
            evaluation("rd-2", "english", "Ms. Noor"),
            evaluation("rd-2", "math", "Ms. Sara"),
            evaluation("rd-1", "math", "Ms. Sara"),
        )
        val vm = viewModel(Kids(maya), Evaluations { answer })
        vm.dispatch(ChildEvaluationsContract.Intent.Load)
        val state = settle(vm.state)
        assertEquals(listOf("rd-2", "rd-1"), state.rounds.map { it.id }, "the newest round is the first heading")
        assertEquals(listOf("english", "math"), state.rounds.first().rows.map { it.subject }, "the server's order by subject is kept")
        assertEquals(1, state.rounds.last().rows.size)
        assertEquals("c1", state.childId)
    }

    @Test fun aRoundSplitAcrossTheAnswerStillReadsAsOneHeading() {
        // Two rows of the same round keep one section even if another round's row sits between them.
        val mixed = listOf(evaluation("rd-2", "math", "Ms. Sara"), evaluation("rd-1", "math", "Ms. Sara"), evaluation("rd-2", "english", "Ms. Noor"))
        assertEquals(listOf("rd-2", "rd-1"), rounds(mixed).map { it.id })
        assertEquals(listOf("math", "english"), rounds(mixed).first().rows.map { it.subject })
    }

    @Test fun theRoundHeadingIsReadInTheParentsOwnLanguage() {
        val round = rounds(listOf(evaluation("rd-2", "math", "Ms. Sara"))).single()
        assertEquals("Term 1 — rd-2", roundName(round, Strings.en))
        assertEquals("الفصل الأول — rd-2", roundName(round, Strings.ar))
        // A round the Admin named in one language only is still a heading rather than an empty one.
        assertEquals("Term 1 — rd-2", roundName(round.copy(nameAr = ""), Strings.ar))
    }

    // ---- 2. words, never numbers

    @Test fun theCriteriaAreReadInTheSpecsOrderWhateverOrderTheyArrivedIn() {
        val shuffled = allFour.reversed()
        assertEquals(
            EvaluationCriterionKey.entries.map { it.key },
            orderedCriteria(shuffled).map { it.criterion },
        )
    }

    @Test fun aCriterionThisBuildDoesNotKnowKeepsItsPlaceAtTheEndRatherThanDisappearing() {
        val withNew = allFour + EvaluationCriterion("curiosity", "good")
        assertEquals("curiosity", orderedCriteria(withNew).last().criterion)
        assertEquals(5, orderedCriteria(withNew).size)
    }

    @Test fun everyPointOfTheScaleHasAWordInBothLanguagesAndNothingReadsAsANumber() {
        for (strings in listOf(Strings.en, Strings.ar)) {
            val s = strings.evaluations
            for (key in listOf("excellent", "good", "developing", "needsSupport")) {
                val word = s.word(key)
                assertTrue(word.isNotBlank() && word != key, "the scale has no word for $key")
                assertNull(word.trim().toIntOrNull(), "the parent is shown a number for $key")
            }
            for (key in EvaluationCriterionKey.entries.map { it.key }) {
                assertTrue(s.criterion(key).isNotBlank() && s.criterion(key) != key, "no name for $key")
            }
            // A key a later server adds is drawn as it arrived rather than hidden.
            assertEquals("curiosity", s.criterion("curiosity"))
            assertEquals("outstanding", s.word("outstanding"))
        }
    }

    @Test fun theShapeTheParentReadsCarriesNoNumberAndNoStateAtAll() {
        // The contract's own guarantee, swept where it is read: encoding a row produces no numeric JSON value and no
        // field a rating, a state or a reviewer could hide in.
        val json = Json.encodeToJsonElement(evaluation("rd-1", "math", "Ms. Sara")).jsonObject
        fun sweep(element: JsonElement, path: String) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    for (forbidden in listOf("rating", "value", "score", "state", "status", "reviewer", "draft")) {
                        assertTrue(!key.contains(forbidden, ignoreCase = true), "$path.$key could carry a '$forbidden'")
                    }
                    sweep(value, "$path.$key")
                }
                is JsonArray -> element.forEachIndexed { i, value -> sweep(value, "$path[$i]") }
                is JsonPrimitive -> assertTrue(
                    element.isString || element is JsonNull,
                    "$path is $element — the parent's shape holds words, never numbers",
                )
            }
        }
        sweep(json, "evaluation")
    }

    // ---- 3. feature 32's teacher row: "Latest evaluation"

    @Test fun theLatestEvaluationLineNamesTheRoundInHerLanguageAndTheSubject() {
        val latest = TeamLatestEvaluation("Term 1 — mid-term", "الفصل الأول — منتصف الفصل", "math")
        assertEquals("Term 1 — mid-term · math", latestLine(latest, Strings.en))
        assertEquals("الفصل الأول — منتصف الفصل · math", latestLine(latest, Strings.ar))
        assertEquals("Term 1 — mid-term · math", latestLine(latest.copy(roundNameAr = ""), Strings.ar))
    }

    // ---- 4. the answers that are not failures

    @Test fun nothingReleasedYetIsAnEmptyPageRatherThanAFailure() = runBlocking {
        val vm = viewModel(Kids(maya), Evaluations { emptyList() })
        vm.dispatch(ChildEvaluationsContract.Intent.Load)
        val state = settle(vm.state)
        assertEquals(emptyList(), state.rounds)
        assertNull(state.errorMessage, "'nothing yet' is not 'something went wrong'")
    }

    @Test fun aRefusalIsToldApartFromAnEmptyPage() = runBlocking {
        val vm = viewModel(Kids(maya), Evaluations { throw ApiException(ApiError(ApiError.NOT_FOUND, "off")) })
        vm.dispatch(ChildEvaluationsContract.Intent.Load)
        val state = settle(vm.state)
        assertTrue(state.errorMessage != null, "'we could not ask' is not 'there is nothing'")
        assertEquals(emptyList(), state.rows)
    }

    @Test fun withNoChildSelectedNothingIsAskedAtAll() = runBlocking {
        val rows = Evaluations { listOf(evaluation("rd-1", "math", "Ms. Sara")) }
        val vm = viewModel(Kids(null), rows)
        vm.dispatch(ChildEvaluationsContract.Intent.Load)
        val state = settle(vm.state)
        assertNull(rows.asked)
        assertEquals(emptyList(), state.rows)
    }

    @Test fun anEvaluationWithoutTheThreeParagraphsStillReadsAsARow() = runBlocking {
        // The comment is required of the teacher, but "going well" and "next step" are not, and an older round may
        // carry none of them: the criteria alone are still an evaluation.
        val bare = evaluation("rd-1", "math", "Ms. Sara", comment = null).copy(goingWell = null, nextStep = null)
        val vm = viewModel(Kids(maya), Evaluations { listOf(bare) })
        vm.dispatch(ChildEvaluationsContract.Intent.Load)
        val row = settle(vm.state).rounds.single().rows.single()
        assertNull(row.comment)
        assertEquals(4, orderedCriteria(row.criteria).size)
    }
}
