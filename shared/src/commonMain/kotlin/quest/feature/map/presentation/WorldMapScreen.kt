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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.LocalDate
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Island
import quest.api.dto.IslandKind
import quest.api.dto.IslandState
import quest.api.dto.Subject
import quest.core.platform.Speaker
import quest.feature.map.presentation.MapContract.Effect
import quest.feature.map.presentation.MapContract.Intent
import quest.feature.map.presentation.MapContract.State
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.ReadAloudButton
import quest.ui.design.RoundIconButton
import quest.ui.design.SpeechBubble
import quest.ui.design.StarRow

@Composable
fun WorldMapRoute(onSwitchChild: () -> Unit, onOpenLesson: (String, Int, Int) -> Unit, onStickers: () -> Unit, onChest: () -> Unit, onGrownUps: () -> Unit, onNeedsChild: () -> Unit) {
    val vm: MapViewModel = koinViewModel()
    val speaker: Speaker = koinInject()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(Intent.Load)
        vm.effects.collect { e ->
            when (e) {
                is Effect.Speak -> speaker.speak(e.text)
                is Effect.OpenLesson -> onOpenLesson(e.lessonId, e.level, e.variant)
                Effect.NeedsChild -> onNeedsChild()
            }
        }
    }
    WorldMapScreen(state, vm::dispatch, onStickers, onChest, onGrownUps, onSwitchChild)
}

@Composable
fun WorldMapScreen(state: State, dispatch: (Intent) -> Unit, onStickers: () -> Unit, onChest: () -> Unit, onGrownUps: () -> Unit, onSwitchChild: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().background(Palette.sea)) {
        Waves()
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Dimens.s16)) {
            Row(Modifier.fillMaxWidth().padding(top = Dimens.s8), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(onSwitchChild, "Switch child") { Pip(PipPose.IDLE, 44.dp, animated = false, color = state.child?.avatarColor ?: "sky") }
                Spacer(Modifier.width(Dimens.s8))
                RoundIconButton(onStickers, "Sticker book") { Text("🌟", fontSize = 26.sp) }
                Spacer(Modifier.width(Dimens.s8))
                RoundIconButton(onChest, "Treasure chest") { Text("🎁", fontSize = 26.sp) }
                Spacer(Modifier.weight(1f))
                if (state.streakDays > 0) { Text("🔥 ${state.streakDays}", style = MaterialTheme.typography.labelLarge, color = Palette.white, modifier = Modifier.semantics { contentDescription = "${state.streakDays} day streak" }); Spacer(Modifier.width(Dimens.s12)) }
                ReadAloudButton({ dispatch(Intent.ReadAloud) })
            }
            Spacer(Modifier.height(Dimens.s12))
            if (state.isEmpty && !state.loading) EmptyMap() else IslandList(state, dispatch)
            Spacer(Modifier.weight(1f))
            Text("Grown-ups", color = Palette.white.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = Dimens.s12).size(width = 140.dp, height = Dimens.minTarget).clickable(role = Role.Button, onClick = onGrownUps).semantics { contentDescription = "Grown-ups" })
        }
    }
}

@Composable
private fun Waves() {
    Canvas(Modifier.fillMaxSize()) { val w = size.width; val h = size.height; for (i in 0..12) { val y = h * i / 12f; drawLine(Palette.seaDeep.copy(alpha = 0.25f), Offset(0f, y), Offset(w, y + 18f), strokeWidth = 3f) } }
}

@Composable
private fun EmptyMap() {
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp, 60.dp).background(Palette.sand, RoundedCornerShape(20.dp)).semantics { contentDescription = "raft" })
        Pip(PipPose.SLEEPING, Dimens.pipLarge)
        Spacer(Modifier.height(Dimens.s16))
        SpeechBubble("No quest today yet. Check the map tomorrow!")
    }
}

/** Islands in list order (review islands small, before today's), oldest at the top; the locked island last. */
@Composable
private fun IslandList(state: State, dispatch: (Intent) -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        val hello = state.child?.name?.takeIf { it.isNotBlank() }?.let { "$it's quest" } ?: "Today's quest"
        Text(hello, style = MaterialTheme.typography.headlineMedium, color = Palette.white, modifier = Modifier.padding(start = Dimens.s8))
        state.islands.forEachIndexed { i, island ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (i % 2 == 0) Arrangement.Start else Arrangement.End) {
                IslandView(island, onClick = { dispatch(Intent.TapIsland(island.id)) })
            }
        }
    }
}

@Composable
private fun IslandView(island: Island, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "glow")
    val glow by transition.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "scale")
    val today = island.state == IslandState.TODAY
    val ground = when {
        island.kind == IslandKind.LOCKED -> Palette.night
        island.state == IslandState.DONE -> Palette.mint
        island.kind == IslandKind.REVIEW -> Palette.peach
        island.subject == Subject.MATH -> Palette.sand
        else -> Palette.lavender
    }
    val width = if (island.kind == IslandKind.REVIEW) 150.dp else 200.dp
    val description = when (island.kind) {
        IslandKind.LOCKED -> "Sleeping island. This island is still asleep."
        IslandKind.REVIEW -> "Review island: ${island.title}"
        IslandKind.LESSON -> "${island.state.name.lowercase()} island: ${island.title}, ${dateLabel(island.date)}"
    }
    Column(
        Modifier.width(width).scale(if (today && island.kind == IslandKind.LESSON) glow else 1f)
            .shadow(if (today) 12.dp else 4.dp, RoundedCornerShape(50), ambientColor = Palette.sun, spotColor = Palette.sun)
            .background(ground, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Dimens.s16, horizontal = Dimens.s12).semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (island.kind) {
            IslandKind.LOCKED -> { Pip(PipPose.SLEEPING, 64.dp); Text("Shh… asleep", style = MaterialTheme.typography.labelLarge, color = Palette.white) }
            else -> {
                Text(dateLabel(island.date), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Palette.ink.copy(alpha = 0.7f))
                Text(if (island.kind == IslandKind.REVIEW) "🔁" else if (island.subject == Subject.MATH) "🔢" else "📖", fontSize = 30.sp)
                Text(island.title, style = MaterialTheme.typography.labelLarge, color = Palette.ink, textAlign = TextAlign.Center, maxLines = 2)
                Spacer(Modifier.height(Dimens.s4))
                when {
                    island.state == IslandState.DONE -> { Text("✓ Done", style = MaterialTheme.typography.labelLarge, color = Palette.ink); StarRow(3, ((island.starsEarned ?: 0) * 3f / (island.starsTotal ?: 1).coerceAtLeast(1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, 3), starSize = 14.dp) }
                    today -> Box(Modifier.background(Palette.sun, CircleShape).padding(horizontal = 14.dp, vertical = 6.dp)) { Text("Play!", style = MaterialTheme.typography.labelLarge, color = Palette.ink) }
                    else -> Text("Waiting", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = Palette.ink.copy(alpha = 0.7f))
                }
            }
        }
    }
}

private fun dateLabel(d: LocalDate): String = "${d.dayOfMonth} ${d.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}"
