package quest.feature.journey.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import quest.ui.design.DashboardTokens
import quest.ui.design.DashboardCard
import quest.ui.design.StarRow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.PublishedLesson
import quest.api.map.MapAssembler
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Speaker
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.LessonRepository
import quest.feature.rewards.domain.AwardStickerUseCase
import quest.feature.rewards.domain.UpdateStreakUseCase
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.journey.Certificate

object CompleteContract {
    data class State(val loading: Boolean = true, val lesson: PublishedLesson? = null, val level: Int = 1, val variant: Int = 0, val stars: Int = 0, val starsTotal: Int = 0, val childName: String = "", val nextLevelUnlocked: Boolean = false) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data object ReadAloud : Intent }
    sealed interface Effect : MviEffect { data class Speak(val text: String) : Effect }
}

class LessonCompleteViewModel(
    private val lessonId: String, private val level: Int, private val variant: Int,
    private val lessons: LessonRepository, private val journey: JourneyRepository, private val children: ChildrenRepository,
    private val awardSticker: AwardStickerUseCase, private val updateStreak: UpdateStreakUseCase, private val copy: LessonCopy,
) : MviViewModel<CompleteContract.State, CompleteContract.Intent, CompleteContract.Effect>(CompleteContract.State(level = level, variant = variant)) {
    init { dispatch(CompleteContract.Intent.Load) }
    override suspend fun handle(intent: CompleteContract.Intent) {
        when (intent) {
            CompleteContract.Intent.Load -> {
                val child = children.currentChild.value ?: return
                val lesson = lessons.lesson(lessonId)
                val play = lesson.play(level, variant) ?: lesson.plays.first()
                val progress = journey.progress(child.id, lessonId, play.level, play.variant)
                awardSticker()
                updateStreak(Today.date())
                val unlocked = MapAssembler.unlockedLevels(journey.completions(child.id).filter { it.lessonId == lessonId }, journey.parentUnlocks(child.id)[lessonId].orEmpty())
                reduce { copy(loading = false, lesson = lesson, stars = progress.starsFor(play), starsTotal = play.stops.size * 3, childName = child.name, nextLevelUnlocked = (level + 1) in unlocked) }
                effect(CompleteContract.Effect.Speak(summary()))
            }
            CompleteContract.Intent.ReadAloud -> effect(CompleteContract.Effect.Speak(summary()))
        }
    }

    private fun summary(): String = copy.strings().speakLessonComplete.replace("{name}", current.childName)
}

@Composable
fun LessonCompleteRoute(lessonId: String, level: Int, variant: Int, onAgain: (String, Int, Int) -> Unit, onNextLevel: (String, Int) -> Unit, onHome: () -> Unit) {
    val vm: LessonCompleteViewModel = koinViewModel(key = "complete-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.effects.collect { if (it is CompleteContract.Effect.Speak) speaker.speak(it.text) } }
    LessonCompleteScreen(state, vm::dispatch, onAgain = { onAgain(lessonId, 1, 1) }, onNextLevel = { onNextLevel(lessonId, level + 1) }, onHome = onHome)
}

/**
 * The result of a finished lesson: a summary card, the stars (a count, never a percentage — §7), the certificate when
 * the school issues them, and where to go next.
 */
@Composable
fun LessonCompleteScreen(state: CompleteContract.State, dispatch: (CompleteContract.Intent) -> Unit, onAgain: () -> Unit, onNextLevel: () -> Unit, onHome: () -> Unit) {
    val s = LocalLessonStrings.current
    val lesson = state.lesson ?: run { LoadingView(s.savingWork); return }
    Column(Modifier.fillMaxSize().background(DashboardTokens.bg).safeDrawingPadding()) {
        LessonTopBar(onBack = null, onReadAloud = { dispatch(CompleteContract.Intent.ReadAloud) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
            DashboardCard(padding = PaddingValues(Dimens.s24)) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(56.dp).background(DashboardTokens.successBg, CircleShape).border(1.dp, DashboardTokens.successBorder, CircleShape), contentAlignment = Alignment.Center) {
                        Text("✓", style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.success)
                    }
                    Spacer(Modifier.height(Dimens.s12))
                    Text(s.lessonComplete, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Dimens.s4))
                    Text(lesson.title, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.ink, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Dimens.s8))
                    Text(s.lessonCompleteBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Dimens.s16))
                    StarRow(total = 3, filled = ((state.stars * 3f) / state.starsTotal.coerceAtLeast(1)).let { kotlin.math.round(it).toInt() }.coerceIn(1, 3))
                    Text(s.starsEarned.replace("{earned}", "${state.stars}").replace("{total}", "${state.starsTotal}"), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                }
            }
            // §4 `certificates`: a school that does not issue them must never show one.
            FeatureGate(Flags.CERTIFICATES) {
                Spacer(Modifier.height(Dimens.s12))
                Certificate(state.childName, lesson.title, state.level, state.stars, state.starsTotal, "${Today.date()}")
            }
            Spacer(Modifier.height(Dimens.s16))
        }
        Column(Modifier.padding(horizontal = Dimens.s16, vertical = Dimens.s12), verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            // §4 `levels.three`: the last level a school sells is where "Next level" stops being offered, and
            // `Routes.Journey` refuses a level above it if the student arrives some other way.
            val hasNext = state.level < Flags.topLevel(featureEnabled(Flags.LEVEL_THREE))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                BigButton(s.repeatLesson, onClick = onAgain, modifier = Modifier.weight(1f), primary = false, compact = true)
                if (hasNext) BigButton(s.nextLevel, onClick = onNextLevel, modifier = Modifier.weight(1f), compact = true, enabled = state.nextLevelUnlocked)
            }
            BigButton(s.backToHome, onClick = onHome, primary = !hasNext)
        }
    }
}
