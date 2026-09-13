package quest.feature.map.presentation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Subject
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.design.Pip
import quest.core.design.PipPose
import quest.core.design.ReadAloudButton
import quest.core.design.RoundIconButton
import quest.core.design.SpeechBubble
import quest.core.design.StarRow
import quest.core.platform.Speaker
import quest.feature.map.domain.Island
import quest.feature.map.domain.IslandStatus
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State

@Composable
fun WorldMapRoute(onOpenIntro: (String) -> Unit, onStickers: () -> Unit, onChest: () -> Unit, onGrownUps: () -> Unit) {
    val vm: MapViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(Intent.Load)
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.OpenIntro -> onOpenIntro(e.skillId)
            }
        }
    }
    WorldMapScreen(state, vm::dispatch, onStickers, onChest, onGrownUps)
}

@Composable
fun WorldMapScreen(state: State, dispatch: (Intent) -> Unit, onStickers: () -> Unit, onChest: () -> Unit, onGrownUps: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.sea)) {
        Waves()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dimens.s16)) {
            Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(onStickers, "Sticker book") { Text("🌟", fontSize = 26.sp) }
                Spacer(Modifier.width(Dimens.s8))
                RoundIconButton(onChest, "Treasure chest") { Text("🎁", fontSize = 26.sp) }
                Spacer(Modifier.weight(1f))
                if (state.streakDays > 0) {
                    Text("🔥 ${state.streakDays}", style = MaterialTheme.typography.labelLarge, color = Palette.white, modifier = Modifier.semantics { contentDescription = "${state.streakDays} day streak" })
                    Spacer(Modifier.width(Dimens.s12))
                }
                ReadAloudButton({ dispatch(Intent.ReadAloud) })
            }
            Spacer(Modifier.height(Dimens.s12))
            if (state.isEmpty) EmptyMap(state.childName)
            else IslandGrid(state, dispatch)
            Spacer(Modifier.weight(1f))
            Text(
                "Grown-ups", color = Palette.white.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = Dimens.s12).size(width = 140.dp, height = Dimens.minTarget).clickable(role = Role.Button, onClick = onGrownUps).semantics { contentDescription = "Grown-ups" },
            )
        }
    }
}

@Composable
private fun Waves() {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        for (i in 0..12) {
            val y = h * i / 12f
            drawLine(Palette.seaDeep.copy(alpha = 0.25f), Offset(0f, y), Offset(w, y + 18f), strokeWidth = 3f)
        }
    }
}

@Composable
private fun EmptyMap(childName: String) {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp, 60.dp).background(Palette.sand, RoundedCornerShape(20.dp)).semantics { contentDescription = "raft" })
        Pip(PipPose.SLEEPING, Dimens.pipLarge, Modifier.padding(top = 0.dp))
        Spacer(Modifier.height(Dimens.s16))
        SpeechBubble("No quest today yet. Ask a grown-up to add today's lesson.")
    }
}

@Composable
private fun IslandGrid(state: State, dispatch: (Intent) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s16)) {
        val hello = if (state.childName.isBlank()) "Today's quest" else "${state.childName}'s quest"
        Text(hello, style = MaterialTheme.typography.headlineMedium, color = Palette.white, modifier = Modifier.padding(start = Dimens.s8))
        state.islands.chunked(2).forEachIndexed { row, pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (row % 2 == 0) Arrangement.Start else Arrangement.End) {
                pair.forEachIndexed { i, island ->
                    IslandView(island, onClick = { dispatch(Intent.TapIsland(island.id)) })
                    if (i == 0 && pair.size > 1) Spacer(Modifier.width(Dimens.s16))
                }
            }
        }
    }
}

@Composable
private fun IslandView(island: Island, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "glow")
    val glow by transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "scale")
    val asleep = island.status == IslandStatus.ASLEEP
    val ground = when {
        asleep -> Palette.night
        island.subject == Subject.MATH -> Palette.sand
        else -> Palette.lavender
    }
    val description = when (island.status) {
        IslandStatus.ASLEEP -> "Sleeping island. This island is still asleep."
        IslandStatus.TODAY -> "Today's island: ${island.name}"
        IslandStatus.DONE -> "Finished island: ${island.name}, ${island.stars} stars"
        IslandStatus.REPLAY -> "Island: ${island.name}, play again"
    }
    Column(
        Modifier.width(180.dp).scale(if (island.status == IslandStatus.TODAY) glow else 1f)
            .shadow(if (island.status == IslandStatus.TODAY) 12.dp else 4.dp, RoundedCornerShape(50), ambientColor = Palette.sun, spotColor = Palette.sun)
            .background(ground, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Dimens.s16, horizontal = Dimens.s12)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (asleep) {
            Pip(PipPose.SLEEPING, 64.dp)
            Text("Shh… asleep", style = MaterialTheme.typography.labelLarge, color = Palette.white)
        } else {
            Text(if (island.subject == Subject.MATH) "🔢" else "🔤", fontSize = 34.sp)
            Text(island.name, style = MaterialTheme.typography.labelLarge, color = Palette.ink, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(Dimens.s4))
            when (island.status) {
                IslandStatus.TODAY -> Box(Modifier.background(Palette.sun, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp)) { Text("Play!", style = MaterialTheme.typography.labelLarge, color = Palette.ink) }
                IslandStatus.DONE, IslandStatus.REPLAY -> StarRow(total = island.total, filled = island.stars, starSize = 14.dp)
                else -> Unit
            }
        }
    }
}
