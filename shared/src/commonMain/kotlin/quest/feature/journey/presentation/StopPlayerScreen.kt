package quest.feature.journey.presentation

import androidx.compose.ui.text.style.TextDirection
import quest.ui.design.DashboardTokens
import quest.ui.design.DashboardProgressBar
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardCard
import quest.core.platform.SpeechLanguages
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.core.platform.Speaker
import quest.feature.journey.presentation.PlayerContract.Effect
import quest.feature.journey.presentation.PlayerContract.Intent
import quest.feature.journey.presentation.PlayerContract.Phase
import quest.feature.journey.presentation.PlayerContract.State
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.NumberLineView
import quest.ui.stops.StopContent
import quest.ui.stops.StopEvent
import quest.ui.stops.LocalExamMode
import androidx.compose.runtime.CompositionLocalProvider
import quest.ui.stops.LocalStopMedia
import quest.core.platform.rememberStopMedia

@Composable
fun StopPlayerRoute(lessonId: String, level: Int, variant: Int, index: Int, onFinished: (String, Int, Int) -> Unit, onBack: () -> Unit) {
    val vm: StopPlayerViewModel = koinViewModel(key = "player-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant, index) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text, SpeechLanguages.of(vm.state.value.lesson?.subject, e.text))
                is Effect.Finished -> onFinished(e.lessonId, e.level, e.variant)
                Effect.BackToJourney -> onBack()
            }
        }
    }
    val media = rememberStopMedia()
    val images: quest.feature.journey.data.LessonImages = org.koin.compose.koinInject()
    val imageLoader = androidx.compose.runtime.remember(state.lesson?.id, state.lesson?.version) { images.loaderFor(state.lesson) }
    // §4: two of the stop's parts are per-school. A school without `retell.recording` gets a recorder that reports
    // itself unavailable, which is exactly how an app with no microphone behaves — the controls are simply not drawn —
    // and one without `openAnswer.drawing` gets no drawing pad. Neither ever produces an error for the child.
    val recording = featureEnabled(Flags.RETELL_RECORDING)
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStopMedia provides if (recording) media else quest.ui.stops.NoStopMedia,
        quest.ui.stops.LocalDrawingEnabled provides featureEnabled(Flags.OPEN_ANSWER_DRAWING),
        quest.ui.stops.LocalStopImageLoader provides imageLoader,
    ) { StopPlayerScreen(state, vm::dispatch, onBack) }
}

@Composable
fun StopPlayerScreen(state: State, dispatch: (Intent) -> Unit, onBack: () -> Unit) {
    val s = LocalLessonStrings.current
    Box(Modifier.fillMaxSize()) {
        when (state.phase) {
            Phase.LOADING -> LoadingView(s.loadingLesson)
            Phase.ERROR -> ErrorView(state.error ?: s.genericError, onBack)
            Phase.DONE -> LoadingView(s.savingWork)
            // §8's "already taken": the server holds a handed-in paper, so there is nothing to sit here.
            Phase.REFUSED -> ErrorView(s.examAlreadyTaken, onBack)
            // Every question is answered but the answers have not all reached the server: not "submitted" yet.
            Phase.SENDING -> SendingView(onRetry = { dispatch(Intent.SendAgain) }, onBack = onBack)
            // The stops read LocalExamMode: one answer each, and nothing that tells right from wrong.
            else -> CompositionLocalProvider(LocalExamMode provides state.exam) { StopView(state, dispatch, onBack) }
        }

        AnimatedVisibility(state.phase == Phase.HINT, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(DashboardTokens.inkStrong.copy(alpha = 0.35f))) }
        AnimatedVisibility(state.phase == Phase.HINT, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            val sheet = RoundedCornerShape(topStart = DashboardTokens.radiusLg, topEnd = DashboardTokens.radiusLg)
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, sheet).border(1.dp, MaterialTheme.colorScheme.outline, sheet).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                HintSheetContent(state, dispatch)
            }
        }
        AnimatedVisibility(state.phase == Phase.CORRECT, enter = fadeIn(), exit = fadeOut()) { ConfirmationOverlay(state.praise) }
        AnimatedVisibility(state.phase == Phase.STEP_DONE, enter = fadeIn(), exit = fadeOut()) {
            // An exam acknowledges every answer with the same two words and the same neutral mark.
            if (state.exam) ConfirmationOverlay(s.answerSaved, neutral = true) else ConfirmationOverlay(s.stepComplete, detail = s.stepsCompleted.replace("{done}", "${state.doneCount}").replace("{total}", "${state.total}"))
        }
    }
}

@Composable
private fun StopView(state: State, dispatch: (Intent) -> Unit, onBack: () -> Unit) {
    val stop = state.stop ?: return
    val s = LocalLessonStrings.current
    val position = (if (state.exam) s.questionOf else s.stepOf).replace("{n}", "${state.index + 1}").replace("{total}", "${state.total}")
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        LessonTopBar(onBack = onBack, onReadAloud = { dispatch(Intent.ReadAloud) }) {
            Text(position, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft, modifier = Modifier.semantics { contentDescription = position })
            Spacer(Modifier.height(Dimens.s4))
            // How many steps are done, as a bar: no percentage and no clock (§7).
            DashboardProgressBar(if (state.total == 0) 0f else state.doneCount.toFloat() / state.total)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            DashboardCard(Modifier.padding(horizontal = Dimens.s16)) {
                Text(stop.speak, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content), color = DashboardTokens.inkStrong, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Dimens.s16))
            StopContent(stop, onEvent = { e ->
                when (e) {
                    is StopEvent.Correct -> dispatch(Intent.Correct(e.attempt, e.answer))
                    is StopEvent.Wrong -> dispatch(Intent.Wrong(e.attempt, e.hint, e.numberLine, e.answer))
                    is StopEvent.Completed -> dispatch(Intent.Completed(e.stars, e.answer, e.mistakes, e.recording, e.drawing, e.correct))
                    is StopEvent.Speak -> dispatch(Intent.Speak(e.text))
                }
            }, childName = state.childName)
            Spacer(Modifier.height(Dimens.s32))
        }
    }
}

@Composable
private fun HintSheetContent(state: State, dispatch: (Intent) -> Unit) {
    val s = LocalLessonStrings.current
    Column(Modifier.fillMaxWidth().padding(Dimens.s24).semantics { contentDescription = s.hint }) {
        DashboardPill(s.hint, variant = DashboardPillVariant.INFO)
        Spacer(Modifier.height(Dimens.s12))
        Text(state.hint, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkStrong)
        state.numberLine?.let { Spacer(Modifier.height(Dimens.s16)); NumberLineView(it) }
        Spacer(Modifier.height(Dimens.s16))
        BigButton(s.tryAgain, onClick = { dispatch(Intent.TryAgain) })
    }
}

/** A finished exam whose answers are still on the device: what that means, and the one thing to do about it. */
@Composable
private fun SendingView(onRetry: () -> Unit, onBack: () -> Unit) {
    val s = LocalLessonStrings.current
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        DashboardCard(padding = PaddingValues(Dimens.s24)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.examSending, style = MaterialTheme.typography.titleLarge, color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s8))
                Text(s.examSendingBody, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(Dimens.s24))
        BigButton(s.examSendAgain, onClick = onRetry)
        Spacer(Modifier.height(Dimens.s12))
        BigButton(s.backToHome, onClick = onBack, primary = false)
    }
}

/** The short confirmation between steps: a tick, the word, and how far along the lesson is. */
@Composable
private fun ConfirmationOverlay(text: String, detail: String? = null, neutral: Boolean = false) {
    Box(Modifier.fillMaxSize().background(DashboardTokens.inkStrong.copy(alpha = 0.35f)).semantics { contentDescription = text }, contentAlignment = Alignment.Center) {
        DashboardCard(Modifier.padding(horizontal = Dimens.s32), padding = PaddingValues(Dimens.s24)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(48.dp).background(if (neutral) MaterialTheme.colorScheme.primaryContainer else DashboardTokens.successBg, CircleShape).border(1.dp, if (neutral) MaterialTheme.colorScheme.primary else DashboardTokens.successBorder, CircleShape), contentAlignment = Alignment.Center) {
                    Text("✓", style = MaterialTheme.typography.titleLarge, color = if (neutral) DashboardTokens.accentInk else DashboardTokens.success)
                }
                Spacer(Modifier.height(Dimens.s12))
                Text(text, style = MaterialTheme.typography.titleLarge, color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
                if (detail != null) {
                    Spacer(Modifier.height(Dimens.s4))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
