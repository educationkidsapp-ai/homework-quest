package quest.feature.journey.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.ReleasedResult
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Speaker
import quest.core.platform.Today
import quest.core.text.isolate
import quest.core.text.longDate
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.map.presentation.examBand
import quest.feature.parent.domain.epochToDate
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.ui.design.BigButton
import quest.ui.design.DashboardCard
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.SubjectMeta

/**
 * M4 (D4): the result of an exam, once the teacher has released it — the score, the level and the teacher's comment,
 * in the formal exam design. Until the release there is no such screen: the card keeps saying the teacher will share
 * the result. It is read from the same released results the parent's Progress shows, so the two always agree.
 */
object ExamResultContract {
    data class State(val loading: Boolean = true, val result: ReleasedResult? = null) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data object ReadAloud : Intent }
    sealed interface Effect : MviEffect { data class Speak(val text: String) : Effect }
}

class ExamResultViewModel(
    private val lessonId: String,
    private val children: ChildrenRepository,
    private val journey: JourneyRepository,
    private val copy: LessonCopy,
) : MviViewModel<ExamResultContract.State, ExamResultContract.Intent, ExamResultContract.Effect>(ExamResultContract.State()) {
    init { dispatch(ExamResultContract.Intent.Load) }

    override suspend fun handle(intent: ExamResultContract.Intent) {
        when (intent) {
            ExamResultContract.Intent.Load -> {
                val child = children.currentChild.value
                val result = child?.let { journey.progressReport(it.id)?.results?.firstOrNull { r -> r.lessonId == lessonId } }
                reduce { copy(loading = false, result = result) }
                effect(ExamResultContract.Effect.Speak(spoken()))
            }
            ExamResultContract.Intent.ReadAloud -> effect(ExamResultContract.Effect.Speak(spoken()))
        }
    }

    private fun spoken(): String {
        val s = copy.strings()
        val r = current.result ?: return s.examResultMissing
        return listOfNotNull(
            s.examResultTitle,
            r.score?.let { "${s.examResultScore} $it" },
            r.band?.let { examBand(it, s) },
            r.comment?.takeIf { it.isNotBlank() }?.let { "${s.examResultComment}: $it" },
        ).joinToString(". ")
    }
}

@Composable
fun ExamResultRoute(lessonId: String, onBack: () -> Unit) {
    GateFallback(Flags.EXAMS, onBack)
    FeatureGate(Flags.EXAMS) {
        val vm: ExamResultViewModel = koinViewModel(key = "exam-result-$lessonId") { parametersOf(lessonId) }
        val speaker: Speaker = koinInject()
        val state by vm.state.collectAsStateWithLifecycle()
        LaunchedEffect(vm) { vm.effects.collect { if (it is ExamResultContract.Effect.Speak) speaker.speak(it.text) } }
        ExamResultScreen(state, vm::dispatch, onBack)
    }
}

@Composable
fun ExamResultScreen(state: ExamResultContract.State, dispatch: (ExamResultContract.Intent) -> Unit, onBack: () -> Unit) {
    val s = LocalLessonStrings.current
    if (state.loading) { LoadingView(s.examResultTitle); return }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LessonTopBar(onBack = onBack, onReadAloud = { dispatch(ExamResultContract.Intent.ReadAloud) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            val r = state.result
            if (r == null) {
                DashboardCard(padding = PaddingValues(Dimens.s24)) {
                    Text(s.examResultMissing, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                return@Column
            }
            DashboardCard(padding = PaddingValues(Dimens.s24)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DashboardPill(text = s.exam, variant = DashboardPillVariant.ACCENT)
                        Spacer(Modifier.width(Dimens.s8))
                        val meta = SubjectMeta.of(r.subject)
                        Text("${meta.emoji} ${meta.label(LocalLessonRtl.current)}", style = MaterialTheme.typography.labelMedium, color = DashboardTokens.inkSoft)
                    }
                    Spacer(Modifier.height(Dimens.s12))
                    Text(s.examResultTitle, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft)
                    Text(
                        isolate(r.title ?: s.exam), style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = DashboardTokens.inkStrong,
                    )
                    Spacer(Modifier.height(Dimens.s16))
                    Row(verticalAlignment = Alignment.Bottom) {
                        r.score?.let {
                            Column {
                                Text(s.examResultScore, style = MaterialTheme.typography.labelMedium, color = DashboardTokens.inkSoft)
                                Text("$it", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.accentInk)
                            }
                            Spacer(Modifier.width(Dimens.s24))
                        }
                        r.band?.let {
                            Column {
                                Text(s.examResultBand, style = MaterialTheme.typography.labelMedium, color = DashboardTokens.inkSoft)
                                Spacer(Modifier.height(Dimens.s4))
                                // A calm pill, never an alarm colour: §7 keeps red off a child's screen.
                                DashboardPill(text = examBand(it, s), variant = DashboardPillVariant.SUCCESS)
                            }
                        }
                    }
                    Spacer(Modifier.height(Dimens.s12))
                    Text(
                        s.examResultShared.replace("{date}", longDate(epochToDate(r.releasedAt), (if (LocalLessonRtl.current) Strings.ar else Strings.en).months, Today.date().year)),
                        style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft,
                    )
                }
            }
            r.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                DashboardCard(padding = PaddingValues(Dimens.s16)) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(s.examResultComment, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = DashboardTokens.inkStrong)
                        Spacer(Modifier.height(Dimens.s4))
                        Text(isolate(comment), style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink)
                    }
                }
            }
            Spacer(Modifier.height(Dimens.s8))
        }
        BigButton(s.backToHome, onClick = onBack, modifier = Modifier.padding(horizontal = Dimens.s16, vertical = Dimens.s12))
    }
}
