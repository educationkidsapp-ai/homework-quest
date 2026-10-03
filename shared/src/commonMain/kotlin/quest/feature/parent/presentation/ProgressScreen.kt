package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.ReleasedResult
import quest.api.progress.Band
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.ProgressReportUseCase
import quest.feature.parent.domain.ReleasedResultsUseCase
import quest.feature.parent.domain.SkillReport
import quest.feature.parent.domain.epochToDate
import quest.core.platform.Today
import quest.core.text.isolate
import quest.core.text.longDate
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.SubjectMeta

object ProgressContract {
    data class State(
        val loading: Boolean = true,
        val reports: List<SkillReport> = emptyList(),
        val streakDays: Int = 0,
        /** Step 9: what the teacher has released, newest first. Empty until she releases something. */
        val results: List<ReleasedResult> = emptyList(),
    ) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent }
    sealed interface Effect : MviEffect
}

class ProgressViewModel(
    private val report: ProgressReportUseCase,
    private val children: ChildrenRepository,
    private val rewards: quest.feature.rewards.domain.RewardsRepository,
    private val released: ReleasedResultsUseCase,
) : MviViewModel<ProgressContract.State, ProgressContract.Intent, ProgressContract.Effect>(ProgressContract.State()) {
    override suspend fun handle(intent: ProgressContract.Intent) {
        val child = children.currentChild.value ?: return
        val r = report(child)
        val streak = rewards.streak().currentDays
        val marks = runCatching { released(child) }.getOrDefault(emptyList())
        reduce { copy(loading = false, reports = r, streakDays = streak, results = marks) }
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // The streak: a count of days, the one figure the student home shows too.
        ParentCard {
            Text("${state.streakDays}", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.inkStrong)
            Spacer(Modifier.height(2.dp))
            Text(s.streak, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
        }

        ReleasedResults(state.results, s)

        SectionTitle(s.weakSkills)
        val weak = state.reports.filter { it.band == Band.NEEDS_ANOTHER_LOOK }
        if (weak.isEmpty()) {
            ParentCard { Text(s.noWeakSkills, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft) }
        }
        weak.forEach { r ->
            ParentCard(Modifier.padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = DashboardTokens.inkStrong, modifier = Modifier.weight(1f))
                    DashboardPill(text = "Needs Review", icon = "↻", variant = DashboardPillVariant.WARNING)
                }
            }
        }

        SectionTitle(s.progress)
        if (state.reports.isEmpty() && !state.loading) {
            ParentCard { Text(s.noLessonsToday, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft) }
        }
        state.reports.forEach { r ->
            val meta = SubjectMeta.of(r.subject)
            ParentCard(Modifier.padding(bottom = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(meta.emoji, fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(r.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = DashboardTokens.inkStrong)
                    }
                    BandPill(r.band, s)
                }
                Spacer(Modifier.height(8.dp))
                r.accuracyWords?.let {
                    Text(
                        "${s.firstTry}: ${s.accuracy(it)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.ink,
                    )
                }
                Text(
                    "${r.attempts} ${s.attempts}" + (r.lastPractised?.let { " · ${s.lastPractised} ${longDate(epochToDate(it), s.months, Today.date().year)}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ReleasedResults(results: List<ReleasedResult>, s: Strings) {
    if (results.isEmpty()) return
    SectionTitle(s.teacherMarks)
    results.forEach { r ->
        val meta = SubjectMeta.of(r.subject)
        ParentCard(Modifier.padding(bottom = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(meta.emoji, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(r.title?.let(::isolate) ?: s.lessonPanel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = DashboardTokens.inkStrong)
                        Text(longDate(r.date, s.months, Today.date().year), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                    }
                }
                r.score?.let {
                    Text("$it", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.accentInk)
                }
            }
            r.band?.let {
                Spacer(Modifier.height(8.dp))
                DashboardPill(
                    text = s.scoreBand(it),
                    variant = when (it.lowercase()) {
                        "exceeding", "secure" -> DashboardPillVariant.SUCCESS
                        "developing" -> DashboardPillVariant.INFO
                        "emerging" -> DashboardPillVariant.WARNING
                        else -> DashboardPillVariant.NEUTRAL
                    },
                )
            }
            r.comment?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(isolate(it), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
            }
            val stopNotesOrFaults = r.stops.filter { !it.comment.isNullOrBlank() || !it.correct }
            if (stopNotesOrFaults.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                stopNotesOrFaults.forEach { stop ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            if (stop.correct) "✓" else "⚠",
                            color = if (stop.correct) DashboardTokens.success else DashboardTokens.warning,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                isolate(stop.title),
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = DashboardTokens.inkStrong,
                            )
                            stop.comment?.takeIf { it.isNotBlank() }?.let { note ->
                                Text(
                                    isolate(note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DashboardTokens.inkSoft,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BandPill(band: Band?, s: Strings) {
    val (label, variant) = when (band) {
        Band.GOING_WELL -> s.goingWell to DashboardPillVariant.SUCCESS
        Band.GETTING_THERE -> s.gettingThere to DashboardPillVariant.INFO
        Band.NEEDS_ANOTHER_LOOK -> s.needsAnotherLook to DashboardPillVariant.WARNING
        null -> s.notPlayedYet to DashboardPillVariant.NEUTRAL
    }
    DashboardPill(text = label, variant = variant)
}

@Composable
fun BandChip(band: Band?, s: Strings) {
    BandPill(band, s)
}
