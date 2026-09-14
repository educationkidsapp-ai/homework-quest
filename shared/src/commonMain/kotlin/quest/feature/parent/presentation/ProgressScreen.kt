package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Subject
import quest.api.progress.Band
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.ProgressReportUseCase
import quest.feature.parent.domain.SkillReport
import quest.feature.parent.domain.epochToDate
import quest.ui.design.Dimens
import quest.ui.design.Palette

object ProgressContract {
    data class State(val loading: Boolean = true, val reports: List<SkillReport> = emptyList(), val streakDays: Int = 0, val stickers: Int = 0) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent }
    sealed interface Effect : MviEffect
}

class ProgressViewModel(private val report: ProgressReportUseCase, private val children: ChildrenRepository, private val rewards: quest.feature.rewards.domain.RewardsRepository) : MviViewModel<ProgressContract.State, ProgressContract.Intent, ProgressContract.Effect>(ProgressContract.State()) {
    override suspend fun handle(intent: ProgressContract.Intent) {
        val child = children.currentChild.value ?: return
        val r = report(child); val streak = rewards.streak().currentDays; val stickers = rewards.stickers().size
        reduce { copy(loading = false, reports = r, streakDays = streak, stickers = stickers) }
    }
}

@Composable
fun ProgressRoute(onBack: () -> Unit) {
    val vm: ProgressViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(ProgressContract.Intent.Load) }
    ParentShell(title = { it.progress }, onBack = onBack) { s -> ProgressScreen(state, s) }
}

/** Screen 21: bands and words, never a percentage. */
@Composable
fun ProgressScreen(state: ProgressContract.State, s: Strings) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        Spacer(Modifier.height(Dimens.s8))
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Dimens.s12)) {
            ParentCard(Modifier.weight(1f)) { Text("🔥 ${state.streakDays}", style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk); Text(s.streak, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) }
            ParentCard(Modifier.weight(1f)) { Text("🌟 ${state.stickers}", style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk); Text(s.stickers, style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft) }
        }
        SectionTitle(s.weakSkills)
        val weak = state.reports.filter { it.band == Band.NEEDS_ANOTHER_LOOK }
        if (weak.isEmpty()) ParentCard { Text(s.noWeakSkills, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft) }
        weak.forEach { r -> ParentCard(Modifier.padding(bottom = Dimens.s8)) { Row(verticalAlignment = Alignment.CenterVertically) { Text(r.name, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f)); Chip("↻", Palette.bandLook) } } }
        SectionTitle(s.progress)
        if (state.reports.isEmpty() && !state.loading) ParentCard { Text(s.noLessonsToday, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft) }
        state.reports.forEach { r ->
            ParentCard(Modifier.padding(bottom = Dimens.s12)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (r.subject == Subject.MATH) "🔢" else "📖", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.padding(Dimens.s4))
                    Text(r.name, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
                    BandChip(r.band, s)
                }
                Spacer(Modifier.height(Dimens.s8))
                r.accuracyWords?.let { Text("${s.firstTry}: ${s.accuracy(it)}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk) }
                Text("${r.attempts} ${s.attempts}" + (r.lastPractised?.let { " · ${s.lastPractised} ${epochToDate(it).dayOfMonth}/${epochToDate(it).monthNumber}" } ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft)
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}

@Composable
fun BandChip(band: Band?, s: Strings) {
    val (label, color) = when (band) {
        Band.GOING_WELL -> s.goingWell to Palette.bandGood
        Band.GETTING_THERE -> s.gettingThere to Palette.bandMid
        Band.NEEDS_ANOTHER_LOOK -> s.needsAnotherLook to Palette.bandLook
        null -> s.notPlayedYet to Palette.parentRule
    }
    Chip(label, color)
}
