package quest.feature.evaluations.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ApiException
import quest.api.dto.EvaluationWord
import quest.api.dto.ParentEvaluation
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.evaluations.domain.ChildEvaluationsRepository
import quest.feature.evaluations.domain.EvaluationRound
import quest.feature.evaluations.domain.orderedCriteria
import quest.feature.evaluations.domain.rounds
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.ParentShell
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 33: **Teacher evaluations** — the one page where a parent reads, in plain words, what each of her child's
 * teachers wrote about her a few times a year.
 *
 * Everything on it is the server's answer. There is no draft, no state, no reviewer and **no number** anywhere in the
 * shape it reads ([ParentEvaluation]), so this screen cannot show one by accident: it draws a round's name, a
 * teacher's name, four word chips and three paragraphs, and offers the one thing a parent does with an evaluation —
 * **Reply privately**, which is her ordinary one-to-one thread with that teacher.
 */
object ChildEvaluationsContract {
    data class State(
        val loading: Boolean = true,
        val errorMessage: String? = null,
        val childId: String? = null,
        val rows: List<ParentEvaluation> = emptyList(),
    ) : MviState {
        /** The page's sections: one per round, newest first, in the order the server answered. */
        val rounds: List<EvaluationRound> get() = rounds(rows)
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
    }

    sealed interface Effect : MviEffect
}

class ChildEvaluationsViewModel(
    private val children: ChildrenRepository,
    private val evaluations: ChildEvaluationsRepository,
) : MviViewModel<ChildEvaluationsContract.State, ChildEvaluationsContract.Intent, ChildEvaluationsContract.Effect>(
    ChildEvaluationsContract.State(),
) {

    override suspend fun handle(intent: ChildEvaluationsContract.Intent) {
        when (intent) {
            ChildEvaluationsContract.Intent.Load -> load()
        }
    }

    private suspend fun load() {
        val child = children.currentChild.value
        if (child == null) {
            reduce { copy(loading = false, childId = null, rows = emptyList()) }
            return
        }
        try {
            val rows = evaluations.evaluations(child.id)
            reduce { copy(loading = false, childId = child.id, errorMessage = null, rows = rows) }
        } catch (e: ApiException) {
            // Nothing released yet is an empty list, not a failure; a refusal is the one thing worth wording.
            reduce { copy(loading = false, childId = child.id, errorMessage = e.message, rows = emptyList()) }
        } catch (e: Throwable) {
            reduce { copy(loading = false, childId = child.id, errorMessage = e.message, rows = emptyList()) }
        }
    }
}

@Composable
fun ChildEvaluationsRoute(
    onBack: () -> Unit,
    onReplyPrivately: (ParentEvaluation) -> Unit,
) {
    val vm: ChildEvaluationsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(vm) { vm.dispatch(ChildEvaluationsContract.Intent.Load) }

    // §4: the entry points are gated too, so this only fires on a tapped push or a switch turned off while she read.
    GateFallback(Flags.CHILD_EVALUATIONS, onBack)
    FeatureGate(Flags.CHILD_EVALUATIONS) {
        ParentShell(title = { it.evaluations.title }, onBack = onBack) { strings ->
            ChildEvaluationsScreen(state = state, strings = strings, onReplyPrivately = onReplyPrivately)
        }
    }
}

@Composable
fun ChildEvaluationsScreen(
    state: ChildEvaluationsContract.State,
    strings: Strings,
    onReplyPrivately: (ParentEvaluation) -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.s16, vertical = Dimens.s8),
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                quest.ui.design.AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
            }
            return
        }

        val problem = when {
            state.errorMessage != null -> strings.somethingWrong
            state.rows.isEmpty() -> strings.evaluations.empty
            else -> null
        }
        if (problem != null) {
            ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                Text(problem, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft)
            }
            return
        }

        state.rounds.forEach { round ->
            SectionTitle(roundName(round, strings))
            round.rows.forEach { row -> EvaluationCard(row, strings, onReplyPrivately) }
        }
        Spacer(Modifier.height(Dimens.s16))
    }
}

/** The round in the parent's language, falling back to the other name rather than to an empty heading. */
fun roundName(round: EvaluationRound, strings: Strings): String =
    if (strings.isRtl) round.nameAr.ifBlank { round.name } else round.name.ifBlank { round.nameAr }

/**
 * One evaluation: who wrote it and about which subject, the four criteria as word chips, then what she wrote — the
 * comment, what is going well and the next step, each only when there is one.
 */
@Composable
private fun EvaluationCard(row: ParentEvaluation, strings: Strings, onReplyPrivately: (ParentEvaluation) -> Unit) {
    val s = strings.evaluations
    val criteria = orderedCriteria(row.criteria)
    ParentCard(
        modifier = Modifier.fillMaxWidth().padding(bottom = Dimens.s8)
            .clearAndSetSemantics {
                contentDescription = (
                    listOf(row.teacherName, row.subject) +
                        criteria.map { "${s.criterion(it.criterion)}: ${s.word(it.word)}" } +
                        listOfNotNull(row.comment, row.goingWell, row.nextStep)
                    ).joinToString(", ")
            },
    ) {
        Text(
            row.teacherName, style = MaterialTheme.typography.titleMedium,
            color = DashboardTokens.ink, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Dimens.s4))
        Text(row.subject, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)

        if (criteria.isNotEmpty()) {
            Spacer(Modifier.height(Dimens.s12))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Dimens.s8),
                verticalArrangement = Arrangement.spacedBy(Dimens.s8),
            ) {
                criteria.forEach { c ->
                    Chip("${s.criterion(c.criterion)} · ${s.word(c.word)}", wordColour(c.word))
                }
            }
        }

        listOf(s.comment to row.comment, s.goingWell to row.goingWell, s.nextStep to row.nextStep)
            .forEach { (heading, body) ->
                if (!body.isNullOrBlank()) {
                    Spacer(Modifier.height(Dimens.s12))
                    Text(heading, style = MaterialTheme.typography.labelMedium, color = DashboardTokens.inkSoft)
                    Spacer(Modifier.height(Dimens.s4))
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
                }
            }

        Spacer(Modifier.height(Dimens.s12))
        Row(Modifier.fillMaxWidth()) {
            ParentButton(
                s.replyPrivately,
                onClick = { onReplyPrivately(row) },
                modifier = Modifier.width(180.dp),
                primary = false,
            )
        }
    }
}

/**
 * The chip's tint, from the palette only, and never the error colour: "needs support" is a sentence a parent reads
 * calmly, the same reason §7 forbids a red cross on the child's side. A word this build does not know is drawn on the
 * plain raised surface rather than guessed at.
 */
@Composable
private fun wordColour(word: String): Color = when (word) {
    EvaluationWord.EXCELLENT -> DashboardTokens.successBg
    EvaluationWord.GOOD -> DashboardTokens.secondarySoft
    EvaluationWord.DEVELOPING -> DashboardTokens.warningBg
    EvaluationWord.NEEDS_SUPPORT -> DashboardTokens.tertiarySoft
    else -> DashboardTokens.surfaceRaised
}
