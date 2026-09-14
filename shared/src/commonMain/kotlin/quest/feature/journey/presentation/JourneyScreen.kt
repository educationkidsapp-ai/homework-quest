package quest.feature.journey.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.core.platform.Speaker
import quest.feature.journey.presentation.JourneyContract.Effect
import quest.feature.journey.presentation.JourneyContract.Intent
import quest.feature.journey.presentation.JourneyContract.State
import quest.ui.design.BackButton
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.ReadAloudButton
import quest.ui.design.RoundIconButton
import quest.ui.journey.JourneyPath
import quest.ui.journey.LevelSelector
import quest.ui.journey.PotView

@Composable
fun JourneyRoute(lessonId: String, level: Int, variant: Int, onOpenStop: (String, Int, Int, Int) -> Unit, onComplete: (String, Int, Int) -> Unit, onParentPanel: (String) -> Unit, onBack: () -> Unit) {
    val vm: JourneyViewModel = koinViewModel(key = "journey-$lessonId-$level-$variant") { parametersOf(lessonId, level, variant) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(Intent.Load)
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.OpenStop -> onOpenStop(e.lessonId, e.level, e.variant, e.index)
                is Effect.OpenComplete -> onComplete(e.lessonId, e.level, e.variant)
            }
        }
    }
    JourneyScreen(state, vm::dispatch, onParentPanel = { onParentPanel(lessonId) }, onBack = onBack)
}

/** The journey: level selector, the path of stops, the pot at the end. */
@Composable
fun JourneyScreen(state: State, dispatch: (Intent) -> Unit, onParentPanel: () -> Unit, onBack: () -> Unit) {
    if (state.error != null) { quest.feature.journey.presentation.ErrorView(state.error, onBack); return }
    if (state.loading || state.play == null) { LoadingView("Getting the journey ready…"); return }
    val play = state.play
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Dimens.s16, vertical = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            RoundIconButton(onParentPanel, "Parent panel") { Text("👩‍🏫", fontSize = 26.sp) }
            Spacer(Modifier.padding(Dimens.s4))
            ReadAloudButton({ dispatch(Intent.ReadAloud) })
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.lesson?.title ?: "", style = MaterialTheme.typography.headlineMedium, color = Palette.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Dimens.s16))
            Spacer(Modifier.height(Dimens.s8))
            LevelSelector(unlocked = state.levelsUnlocked, completed = state.completedLevels, current = state.level, onSelect = { dispatch(Intent.SelectLevel(it)) })
            Spacer(Modifier.height(Dimens.s12))
            JourneyPath(stops = state.stops, states = state.nodeStates, stars = state.stops.map { state.stopStars[it.id] }, onTap = { dispatch(Intent.TapStop(it)) })
            Spacer(Modifier.height(Dimens.s16))
            PotView(play.theme, state.collected, state.stops.size, modifier = Modifier.clickable(enabled = state.complete) { dispatch(Intent.Serve) })
            Spacer(Modifier.height(Dimens.s16))
            if (state.complete) BigButton("Serve the ${play.theme.dishName}", onClick = { dispatch(Intent.Serve) }, emoji = play.theme.potEmoji, modifier = Modifier.padding(horizontal = Dimens.s24))
            else BigButton(if (state.collected.isEmpty()) "Start the journey" else "Next stop", onClick = { dispatch(Intent.TapStop(state.nextIndex)) }, emoji = "🚀", modifier = Modifier.padding(horizontal = Dimens.s24))
            Spacer(Modifier.height(Dimens.s24))
        }
    }
}

@Composable
fun LoadingView(text: String) {
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        quest.ui.design.Pip(quest.ui.design.PipPose.THINKING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        androidx.compose.material3.CircularProgressIndicator(color = Palette.sunDeep)
        Spacer(Modifier.height(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorView(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Palette.sky).safeDrawingPadding().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        quest.ui.design.Pip(quest.ui.design.PipPose.SLEEPING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s24))
        BigButton("Back to the map", onClick = onBack)
    }
}
