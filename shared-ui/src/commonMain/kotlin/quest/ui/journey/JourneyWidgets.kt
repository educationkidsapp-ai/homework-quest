package quest.ui.journey

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.Ingredient
import quest.api.dto.Stop
import quest.api.dto.Theme
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose
import quest.ui.design.StarRow

/** The pot at the end of the journey: collected ingredients float in it; full when every stop is done. */
@Composable
fun PotView(theme: Theme, collected: List<Ingredient>, total: Int, modifier: Modifier = Modifier, size: Int = 140) {
    val fill by animateFloatAsState(if (total == 0) 0f else collected.size.toFloat() / total, spring(), label = "pot")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size.dp).semantics { contentDescription = "${theme.potName}: ${collected.size} of $total ingredients" }, contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth().height((size * (0.25f + 0.6f * fill)).dp).background(Palette.coral.copy(alpha = 0.35f), RoundedCornerShape(bottomStart = 40.dp, bottomEnd = 40.dp, topStart = 12.dp, topEnd = 12.dp)))
            Text(theme.potEmoji, fontSize = (size * 0.6f).sp, modifier = Modifier.align(Alignment.Center))
            Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                collected.takeLast(5).forEach { Text(it.emoji, fontSize = 20.sp) }
            }
        }
        Text("${collected.size} / $total", style = MaterialTheme.typography.labelLarge, color = Palette.ink)
    }
}

enum class NodeState { DONE, CURRENT, LOCKED }

/** The path of stops: numbered nodes with the ingredient emoji, connected top to bottom, the pot at the end. */
@Composable
fun JourneyPath(stops: List<Stop>, states: List<NodeState>, stars: List<Int?>, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        stops.forEachIndexed { i, stop ->
            val state = states.getOrElse(i) { NodeState.LOCKED }
            val scale by animateFloatAsState(if (state == NodeState.CURRENT) 1.06f else 1f, spring(dampingRatio = 0.6f), label = "node")
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Dimens.s24, vertical = 6.dp).scale(scale)
                    .alpha(if (state == NodeState.LOCKED) 0.55f else 1f)
                    .shadow(if (state == NodeState.CURRENT) 10.dp else 3.dp, RoundedCornerShape(Dimens.radiusTile), ambientColor = Palette.sun, spotColor = Palette.sun)
                    .background(when (state) { NodeState.DONE -> Palette.mint; NodeState.CURRENT -> Palette.sun; NodeState.LOCKED -> Palette.cream }, RoundedCornerShape(Dimens.radiusTile))
                    .clickable(enabled = state != NodeState.LOCKED, role = Role.Button) { onTap(i) }
                    .padding(Dimens.s12)
                    .semantics { contentDescription = "Stop ${i + 1}: ${stop.title}, ${state.name.lowercase()}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).background(Palette.white.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (state == NodeState.DONE) stop.ingredient.emoji else "${i + 1}", fontSize = 24.sp, color = Palette.ink)
                }
                Spacer(Modifier.width(Dimens.s12))
                Column(Modifier.weight(1f)) {
                    Text(stop.title, style = MaterialTheme.typography.labelLarge, color = Palette.ink)
                    Text(stopKindLabel(stop), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp), color = Palette.inkSoft)
                }
                stars.getOrNull(i)?.let { StarRow(3, it, starSize = 16.dp) }
                if (state == NodeState.CURRENT) Pip(PipPose.WAVING, 44.dp)
            }
            if (i < stops.lastIndex) Box(Modifier.width(6.dp).height(14.dp).background(Palette.inkSoft.copy(alpha = 0.3f), RoundedCornerShape(3.dp)))
        }
    }
}

fun stopKindLabel(stop: Stop): String = when (stop) {
    is Stop.ReadPage -> "Read the page"; is Stop.StoryPieces -> "Story pieces"; is Stop.WordCards -> "New words"; is Stop.Move -> "Move"; is Stop.Explain -> "Learn"
    is Stop.Choice, is Stop.TrueFalse, is Stop.Sequence, is Stop.Count, is Stop.Compare, is Stop.Sound, is Stop.Word, is Stop.ReadTap -> "Game"
    is Stop.MultiSelect, is Stop.SelectAll -> "Tap game"; is Stop.Match -> "Match up"; is Stop.Order -> "Put in order"; is Stop.Trace -> "Trace"
    is Stop.Retell -> "Tell the story"; is Stop.OpenAnswer -> "Your idea"; is Stop.WriteSentence -> "Finish the sentence"; is Stop.ExitTicket -> "Exit ticket"
}

/** 1 · 2 · 3 selector; locked levels are asleep. */
@Composable
fun LevelSelector(unlocked: List<Int>, completed: List<Int>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val names = mapOf(1 to "Same as the book", 2 to "Think", 3 to "Challenge")
    Row(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        (1..3).forEach { lvl ->
            val open = lvl in unlocked
            Column(
                Modifier.weight(1f).height(Dimens.minTarget + 8.dp)
                    .background(if (lvl == current) Palette.sun else if (open) Palette.cream else Palette.night, RoundedCornerShape(18.dp))
                    .border(2.dp, if (lvl == current) Palette.sunDeep else Color.Transparent, RoundedCornerShape(18.dp))
                    .clickable(enabled = open, role = Role.Button) { onSelect(lvl) }.padding(6.dp)
                    .semantics { contentDescription = "Level $lvl ${names[lvl]}" + if (!open) ", asleep" else if (lvl in completed) ", done" else "" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(if (open) "$lvl" + (if (lvl in completed) " ✓" else "") else "💤", style = MaterialTheme.typography.titleLarge, color = if (open) Palette.ink else Palette.white)
                Text(names[lvl] ?: "", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 14.sp), color = if (open) Palette.inkSoft else Palette.white.copy(alpha = 0.8f), textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** The certificate shown when the pot is served. */
@Composable
fun Certificate(childName: String, lessonTitle: String, level: Int, stars: Int, starsTotal: Int, dateText: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = Dimens.s16).shadow(8.dp, RoundedCornerShape(Dimens.radiusCard))
            .background(Palette.cream, RoundedCornerShape(Dimens.radiusCard)).border(4.dp, Palette.sun, RoundedCornerShape(Dimens.radiusCard)).padding(Dimens.s24)
            .semantics { contentDescription = "Certificate for $childName: $lessonTitle level $level, $stars of $starsTotal stars" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🏅", fontSize = 48.sp)
        Text("Certificate", style = MaterialTheme.typography.headlineMedium, color = Palette.ink)
        Spacer(Modifier.height(Dimens.s8))
        Text(childName.ifBlank { "Explorer" }, style = MaterialTheme.typography.displayLarge, color = Palette.ink, textAlign = TextAlign.Center)
        Text("finished", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft)
        Text(lessonTitle, style = MaterialTheme.typography.titleLarge, color = Palette.ink, textAlign = TextAlign.Center)
        Text("Level $level", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft)
        Spacer(Modifier.height(Dimens.s8))
        StarRow(total = 3, filled = ((stars * 3f) / starsTotal.coerceAtLeast(1)).let { kotlin.math.round(it).toInt() }.coerceIn(1, 3), starSize = 30.dp)
        Spacer(Modifier.height(Dimens.s8))
        Text(dateText, style = MaterialTheme.typography.bodyMedium, color = Palette.inkSoft)
    }
}
