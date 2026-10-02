package quest.feature.journey.presentation

import androidx.compose.ui.platform.testTag
import quest.ui.design.TestTags
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.core.platform.Speaker
import quest.core.platform.SpeechLanguages
import quest.feature.journey.presentation.JourneyContract.Effect
import quest.feature.journey.presentation.JourneyContract.Intent
import quest.feature.journey.presentation.JourneyContract.State
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.BackButton
import quest.ui.design.BigButton
import quest.ui.design.DashboardCard
import quest.ui.design.DashboardHeroCard
import quest.ui.design.DashboardProgressBar
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.ReadAloudButton
import quest.ui.design.SubjectMeta
import quest.ui.journey.LevelSelector
import quest.ui.journey.StepList

@Composable
fun JourneyRoute(lessonId: String, level: Int, variant: Int, onOpenStop: (String, Int, Int, Int) -> Unit, onComplete: (String, Int, Int) -> Unit, onBack: () -> Unit) {
    val vm: JourneyViewModel = koinViewModel(key = "journey-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(Intent.Load)
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text, SpeechLanguages.of(vm.state.value.lesson?.subject, e.text))
                is Effect.OpenStop -> onOpenStop(e.lessonId, e.level, e.variant, e.index)
                is Effect.OpenComplete -> onComplete(e.lessonId, e.level, e.variant)
            }
        }
    }
    JourneyScreen(state, vm::dispatch, onBack = onBack)
}

/**
 * The lesson overview: what the lesson is, how far the student is, its levels and its steps.
 */
@Composable
fun JourneyScreen(state: State, dispatch: (Intent) -> Unit, onBack: () -> Unit) {
    val s = LocalLessonStrings.current
    if (state.error != null) { ErrorView(state.error, onBack); return }
    if (state.loading || state.play == null) { LoadingView(s.loadingLesson); return }
    val total = state.stops.size
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LessonTopBar(onBack = onBack, onReadAloud = { dispatch(Intent.ReadAloud) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            DashboardHeroCard(Modifier.padding(horizontal = Dimens.s16)) {
                val onHero = MaterialTheme.colorScheme.onPrimary
                Column {
                    if (state.exam) { Text(s.exam.uppercase(), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = onHero); Spacer(Modifier.height(Dimens.s4)) }
                    Text(state.lesson?.subject?.let { SubjectMeta.of(it).label(LocalLessonRtl.current) }.orEmpty(), style = MaterialTheme.typography.labelLarge, color = onHero.copy(alpha = 0.9f))
                    Spacer(Modifier.height(Dimens.s4))
                    Text(state.lesson?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = onHero)
                    Spacer(Modifier.height(Dimens.s12))
                    // A bar and a count of steps — never a percentage (§7).
                    DashboardProgressBar(if (total == 0) 0f else state.doneCount.toFloat() / total, color = onHero, trackColor = onHero.copy(alpha = 0.3f))
                    Spacer(Modifier.height(Dimens.s4))
                    Text((if (state.exam) s.questionsAnswered else s.stepsCompleted).replace("{done}", "${state.doneCount}").replace("{total}", "$total"), style = MaterialTheme.typography.bodySmall, color = onHero)
                }
            }
            Spacer(Modifier.height(Dimens.s12))
            // §4 `levels.three`: off, the third level is not offered at all.
            if (state.exam) DashboardCard(Modifier.padding(horizontal = Dimens.s16)) {
                // §8: one paper, one sitting — no level to choose, and the rules said before the first question.
                Text(s.examRules, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink, modifier = Modifier.fillMaxWidth())
            } else LevelSelector(unlocked = state.levelsUnlocked, completed = state.completedLevels, current = state.level, onSelect = { dispatch(Intent.SelectLevel(it)) }, levels = Flags.levels(featureEnabled(Flags.LEVEL_THREE)))
            Spacer(Modifier.height(Dimens.s12))
            StepList(stops = state.stops, states = state.nodeStates, stars = state.stops.map { if (state.exam) null else state.stopStars[it.id] }, onTap = { dispatch(Intent.TapStop(it)) }, exam = state.exam)
            Spacer(Modifier.height(Dimens.s16))
        }
        val cta = when {
            state.exam && state.complete -> s.examSubmitted
            state.exam && state.doneCount == 0 -> s.startExam
            state.exam -> s.continueExam
            state.complete -> s.finishLesson
            state.doneCount == 0 -> s.startLesson
            else -> s.continueLesson
        }
        BigButton(cta, onClick = { dispatch(if (state.complete) Intent.Finish else Intent.TapStop(state.nextIndex)) }, modifier = Modifier.padding(horizontal = Dimens.s16, vertical = Dimens.s12).testTag(TestTags.LESSON_CTA))
    }
}

/** Back on the leading edge, read-aloud on the trailing one, and whatever the screen puts between them. */
@Composable
fun LessonTopBar(onBack: (() -> Unit)?, onReadAloud: () -> Unit, center: @Composable () -> Unit = {}) {
    val s = LocalLessonStrings.current
    Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) BackButton(onBack, contentDescription = s.back)
        Spacer(Modifier.width(Dimens.s12))
        Column(Modifier.weight(1f)) { center() }
        Spacer(Modifier.width(Dimens.s12))
        ReadAloudButton(onReadAloud, contentDescription = s.readAloud)
    }
}

@Composable
fun LoadingView(text: String) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
        Spacer(Modifier.height(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorView(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        DashboardCard {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkStrong, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Dimens.s24))
        BigButton(LocalLessonStrings.current.backToHome, onClick = onBack)
    }
}
