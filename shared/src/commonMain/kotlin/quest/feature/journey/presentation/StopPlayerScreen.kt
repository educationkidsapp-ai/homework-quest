package quest.feature.journey.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.core.platform.Speaker
import quest.feature.journey.presentation.PlayerContract.Effect
import quest.feature.journey.presentation.PlayerContract.Intent
import quest.feature.journey.presentation.PlayerContract.Phase
import quest.feature.journey.presentation.PlayerContract.State
import quest.ui.design.BackButton
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.NumberLineView
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.ReadAloudButton
import quest.ui.design.SpeechBubble
import quest.ui.journey.PotView
import quest.ui.stops.StopContent
import quest.ui.stops.StopEvent

@Composable
fun StopPlayerRoute(lessonId: String, level: Int, variant: Int, index: Int, onFinished: (String, Int, Int) -> Unit, onBack: () -> Unit) {
    val vm: StopPlayerViewModel = koinViewModel(key = "player-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant, index) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.Finished -> onFinished(e.lessonId, e.level, e.variant)
                Effect.BackToJourney -> onBack()
            }
        }
    }
    StopPlayerScreen(state, vm::dispatch, onBack)
}

@Composable
fun StopPlayerScreen(state: State, dispatch: (Intent) -> Unit, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.sky)) {
        when (state.phase) {
            Phase.LOADING -> LoadingView("Getting ready…")
            Phase.ERROR -> ErrorView(state.error ?: "Something went wrong.", onBack)
            Phase.DONE -> LoadingView("Serving…")
            else -> StopView(state, dispatch, onBack)
        }

        AnimatedVisibility(state.phase == Phase.HINT, enter = fadeIn(), exit = fadeOut()) { Box(Modifier.fillMaxSize().background(Palette.ink.copy(alpha = 0.35f))) }
        AnimatedVisibility(state.phase == Phase.HINT, modifier = Modifier.align(Alignment.BottomCenter), enter = slideInVertically(initialOffsetY = { it }) + fadeIn(), exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()) {
            Box(Modifier.fillMaxWidth().shadow(12.dp, RoundedCornerShape(topStart = Dimens.radiusSheet, topEnd = Dimens.radiusSheet)).background(Palette.peach, RoundedCornerShape(topStart = Dimens.radiusSheet, topEnd = Dimens.radiusSheet)).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                HintSheetContent(state, dispatch)
            }
        }
        AnimatedVisibility(state.phase == Phase.CORRECT, enter = fadeIn(), exit = fadeOut()) { CorrectOverlay(state.praise) }
        AnimatedVisibility(state.phase == Phase.INGREDIENT, enter = fadeIn(), exit = fadeOut()) { IngredientOverlay(state) }
    }
}

@Composable
private fun StopView(state: State, dispatch: (Intent) -> Unit, onBack: () -> Unit) {
    val stop = state.stop ?: return
    val play = state.play ?: return
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text("${state.index + 1} / ${state.total}", style = MaterialTheme.typography.labelLarge, color = Palette.inkSoft, modifier = Modifier.semantics { contentDescription = "stop ${state.index + 1} of ${state.total}" })
            Spacer(Modifier.padding(Dimens.s8))
            PotView(play.theme, state.collected, state.total, size = 56)
            Spacer(Modifier.weight(1f))
            ReadAloudButton({ dispatch(Intent.ReadAloud) })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.padding(horizontal = Dimens.s16), verticalAlignment = Alignment.CenterVertically) {
                Pip(PipPose.IDLE, Dimens.pipSmall, color = "sky")
                Spacer(Modifier.padding(Dimens.s4))
                SpeechBubble(stop.speak, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.s16))
            StopContent(stop, onEvent = { e ->
                when (e) {
                    is StopEvent.Correct -> dispatch(Intent.Correct(e.attempt, e.answer))
                    is StopEvent.Wrong -> dispatch(Intent.Wrong(e.attempt, e.hint, e.numberLine, ""))
                    is StopEvent.Completed -> dispatch(Intent.Completed(e.stars, e.answer, e.mistakes))
                    is StopEvent.Speak -> dispatch(Intent.Speak(e.text))
                }
            }, childName = state.childName)
            Spacer(Modifier.height(Dimens.s32))
        }
    }
}

@Composable
private fun HintSheetContent(state: State, dispatch: (Intent) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(Dimens.s24).semantics { contentDescription = "Hint" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pip(PipPose.THINKING, Dimens.pipSmall)
            Spacer(Modifier.padding(Dimens.s4))
            SpeechBubble(state.hint, Modifier.weight(1f), color = Palette.cream)
        }
        state.numberLine?.let { Spacer(Modifier.height(Dimens.s16)); NumberLineView(it) }
        Spacer(Modifier.height(Dimens.s16))
        BigButton("Try again", onClick = { dispatch(Intent.TryAgain) }, emoji = "💪")
        Spacer(Modifier.height(Dimens.s16))
    }
}

@Composable
private fun CorrectOverlay(praise: String) {
    val scale by animateFloatAsState(1f, spring(dampingRatio = 0.45f), label = "pop")
    Box(Modifier.fillMaxSize().background(Palette.mint.copy(alpha = 0.94f)).semantics { contentDescription = "Correct! $praise" }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⭐  ⭐  ⭐", fontSize = 40.sp, modifier = Modifier.scale(scale))
            Spacer(Modifier.height(Dimens.s16))
            Pip(PipPose.CELEBRATING, Dimens.pipLarge)
            Spacer(Modifier.height(Dimens.s16))
            Text(praise, style = MaterialTheme.typography.displayLarge, color = Palette.ink)
        }
    }
}

/** The finished stop's ingredient drops into the pot. */
@Composable
private fun IngredientOverlay(state: State) {
    val scale by animateFloatAsState(1f, spring(dampingRatio = 0.4f), label = "drop")
    val play = state.play ?: return
    Box(Modifier.fillMaxSize().background(Palette.sun.copy(alpha = 0.92f)).semantics { contentDescription = "${state.lastIngredient?.name} goes in the pot" }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.lastIngredient?.emoji ?: "", fontSize = 72.sp, modifier = Modifier.scale(scale))
            Text("${state.lastIngredient?.name ?: ""} goes in!", style = MaterialTheme.typography.displayLarge, color = Palette.ink)
            Spacer(Modifier.height(Dimens.s16))
            PotView(play.theme, state.collected, state.total, size = 160)
            Spacer(Modifier.height(Dimens.s8))
            Pip(PipPose.CELEBRATING, Dimens.pipMedium)
        }
    }
}
