package quest.feature.practice.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import quest.api.dto.GenerateMode
import quest.api.dto.Question
import quest.core.design.BackButton
import quest.core.design.BigButton
import quest.core.design.Dimens
import quest.core.design.NumberLineView
import quest.core.design.Palette
import quest.core.design.Pip
import quest.core.design.PipPose
import quest.core.design.ReadAloudButton
import quest.core.design.SpeechBubble
import quest.core.design.StarRow
import quest.core.design.StickerKeys
import quest.core.platform.Speaker
import quest.feature.practice.presentation.PracticeContract.Effect
import quest.feature.practice.presentation.PracticeContract.Intent
import quest.feature.practice.presentation.PracticeContract.Phase
import quest.feature.practice.presentation.PracticeContract.State

@Composable
fun PracticeRoute(setId: String, onOpenSet: (String) -> Unit, onMap: () -> Unit, onStickers: () -> Unit) {
    val vm: PracticeViewModel = koinViewModel(key = "practice-$setId") { parametersOf(setId) }
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.OpenSet -> onOpenSet(e.setId)
                Effect.GoToMap -> onMap()
                Effect.GoToStickers -> onStickers()
            }
        }
    }
    PracticeScreen(state, vm::dispatch, onMap = onMap, onStickers = onStickers)
}

@Composable
fun PracticeScreen(state: State, dispatch: (Intent) -> Unit, onMap: () -> Unit, onStickers: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.sky)) {
        when (state.phase) {
            Phase.LOADING, Phase.GENERATING -> LoadingView(if (state.phase == Phase.GENERATING) "Pip is making new questions…" else "Getting ready…")
            Phase.ERROR -> ErrorView(state.errorMessage ?: "Something went wrong.", onMap)
            Phase.COMPLETE -> SetCompleteView(state, dispatch, onMap, onStickers)
            else -> QuestionView(state, dispatch, onMap)
        }

        // Hint sheet drawn in-tree (no dialog window) so it behaves identically on every platform.
        AnimatedVisibility(state.phase == Phase.HINT, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Palette.ink.copy(alpha = 0.35f)))
        }
        AnimatedVisibility(
            state.phase == Phase.HINT,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(topStart = Dimens.radiusSheet, topEnd = Dimens.radiusSheet))
                    .background(Palette.peach, RoundedCornerShape(topStart = Dimens.radiusSheet, topEnd = Dimens.radiusSheet))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) { HintSheetContent(state, dispatch) }
        }

        AnimatedVisibility(state.phase == Phase.CORRECT, enter = fadeIn(), exit = fadeOut()) { CorrectOverlay(state) }
    }
}

@Composable
private fun QuestionView(state: State, dispatch: (Intent) -> Unit, onMap: () -> Unit) {
    val q = state.question ?: return
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dimens.s16)) {
        Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onMap)
            Spacer(Modifier.weight(1f))
            StarRow(total = state.total, filled = state.stars, starSize = 22.dp)
            Spacer(Modifier.weight(1f))
            ReadAloudButton({ dispatch(Intent.ReadAloud) })
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(Dimens.s12))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pip(PipPose.IDLE, Dimens.pipSmall)
                Spacer(Modifier.padding(Dimens.s4))
                SpeechBubble(state.prompt, Modifier.weight(1f))
            }
            Spacer(Modifier.height(Dimens.s24))
            if (q is Question.Trace) {
                TraceCanvas(q.letter.first(), onFinished = { dispatch(Intent.TraceFinished(it)) })
            } else {
                QuestionPrompt(q, onSpeak = { dispatch(Intent.SpeakWord(it)) })
                Spacer(Modifier.height(Dimens.s32))
                AnswerTiles(q, state.dimmed, onAnswer = { dispatch(Intent.Answer(it)) })
            }
            Spacer(Modifier.height(Dimens.s24))
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
        state.numberLine?.let {
            Spacer(Modifier.height(Dimens.s16))
            NumberLineView(it)
        }
        Spacer(Modifier.height(Dimens.s16))
        BigButton("Try again", onClick = { dispatch(Intent.TryAgain) }, emoji = "💪")
        Spacer(Modifier.height(Dimens.s16))
    }
}

/** Full-screen ink wash with star pops and Pip celebrating; auto-advances (see PracticeViewModel). */
@Composable
private fun CorrectOverlay(state: State) {
    val scale by animateFloatAsState(1f, spring(dampingRatio = 0.45f), label = "pop")
    Box(Modifier.fillMaxSize().background(Palette.mint.copy(alpha = 0.94f)).semantics { contentDescription = "Correct! ${state.praise}" }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⭐  ⭐  ⭐", fontSize = 40.sp, modifier = Modifier.scale(scale))
            Spacer(Modifier.height(Dimens.s16))
            Pip(PipPose.CELEBRATING, Dimens.pipLarge)
            Spacer(Modifier.height(Dimens.s16))
            Text(state.praise, style = MaterialTheme.typography.displayLarge, color = Palette.ink)
            if (state.question is Question.Trace && state.traceStars > 0) {
                Spacer(Modifier.height(Dimens.s8))
                StarRow(total = 3, filled = state.traceStars, starSize = 32.dp)
            }
        }
    }
}

@Composable
private fun SetCompleteView(state: State, dispatch: (Intent) -> Unit, onMap: () -> Unit, onStickers: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.s24).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) { Spacer(Modifier.weight(1f)); ReadAloudButton({ dispatch(Intent.ReadAloud) }) }
        Pip(PipPose.CELEBRATING, Dimens.pipLarge)
        Text("You did it!", style = MaterialTheme.typography.displayLarge, color = Palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s12))
        StarRow(total = state.total, filled = state.stars, starSize = 30.dp)
        Spacer(Modifier.height(Dimens.s24))
        state.stickerKey?.let { key ->
            Column(Modifier.background(Palette.cream, MaterialTheme.shapes.extraLarge).padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("New sticker!", style = MaterialTheme.typography.titleLarge, color = Palette.ink)
                Text(StickerKeys.emoji(key), fontSize = 72.sp, modifier = Modifier.semantics { contentDescription = "sticker $key" })
            }
        }
        Spacer(Modifier.height(Dimens.s24))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            BigButton("Again", onClick = { dispatch(Intent.More(GenerateMode.AGAIN)) }, modifier = Modifier.weight(1f), emoji = "🔁", compact = true)
            BigButton("Harder", onClick = { dispatch(Intent.More(GenerateMode.HARDER)) }, modifier = Modifier.weight(1f), emoji = "🚀", color = Palette.lavender, compact = true)
        }
        Spacer(Modifier.height(Dimens.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            BigButton("Stickers", onClick = onStickers, modifier = Modifier.weight(1f), emoji = "🌟", color = Palette.cream, compact = true)
            BigButton("Map", onClick = onMap, modifier = Modifier.weight(1f), emoji = "🗺️", color = Palette.cream, compact = true)
        }
    }
}

@Composable
fun LoadingView(text: String) {
    Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Pip(PipPose.THINKING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        CircularProgressIndicator(color = Palette.sunDeep)
        Spacer(Modifier.height(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
    }
}

@Composable
fun ErrorView(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(Dimens.s24), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Pip(PipPose.SLEEPING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Dimens.s24))
        BigButton("Back to the map", onClick = onBack)
    }
}
