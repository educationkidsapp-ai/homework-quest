package quest.ui.stops

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.NumberLine
import quest.api.dto.Stop
import quest.api.dto.Tile
import quest.ui.design.AnswerTile
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.Illustration
import quest.ui.design.Palette

/** One answer tile spec (label and/or picture). */
data class TileSpec(val id: String, val label: String? = null, val picture: String? = null)

fun Tile.spec() = TileSpec(id, label, illustrationKey)

/** 2-up grid of 176×100 tiles, 12 dp gaps. `state` colours a tile: null = normal, true = lit (correct), false = dimmed. */
@Composable
fun TileGrid(tiles: List<TileSpec>, onTap: (String) -> Unit, modifier: Modifier = Modifier, dimmed: Set<String> = emptySet(), lit: Set<String> = emptySet(), selected: Set<String> = emptySet(), fontSize: Int = 32) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            if (row > 0) Spacer(Modifier.height(Dimens.tileGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileGap)) {
                pair.forEach { t ->
                    val color = when { t.id in lit -> Palette.mint; t.id in selected -> Palette.sun; else -> Palette.cream }
                    AnswerTile(
                        label = t.label ?: t.picture ?: "", onClick = { onTap(t.id) }, dimmed = t.id in dimmed, color = color,
                        fontSize = if ((t.label?.length ?: 0) > 8) 22 else fontSize,
                        content = if (t.picture != null && t.label == null) ({ Illustration(t.picture, 84.dp, corner = 18.dp) })
                        else if (t.picture != null) ({
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Illustration(t.picture, 56.dp, corner = 14.dp); Spacer(Modifier.width(8.dp))
                                Text(t.label!!, fontSize = if (t.label.length > 10) 18.sp else 22.sp, color = Palette.ink, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                            }
                        }) else null,
                    )
                }
            }
        }
    }
}

/** Shared state machine for single-answer stops: dims wrong tiles, counts attempts, reports Correct / Wrong. */
@Composable
fun rememberSingleAnswer(stop: Stop.SingleAnswer, onEvent: (StopEvent) -> Unit): SingleAnswerState =
    rememberSingleAnswer(stop.id, stop.correctId, stop.hint, numberLineOf(stop), onEvent)

@Composable
fun rememberSingleAnswer(stopId: String, correctId: String, hint: String, numberLine: NumberLine?, onEvent: (StopEvent) -> Unit): SingleAnswerState {
    var dimmed by rememberSaveable(stopId) { mutableStateOf(setOf<String>()) }
    var attempts by rememberSaveable(stopId) { mutableIntStateOf(0) }
    var done by rememberSaveable(stopId) { mutableStateOf(false) }
    return SingleAnswerState(dimmed, done) { id ->
        if (done || id in dimmed) return@SingleAnswerState
        attempts += 1
        if (id == correctId) { done = true; onEvent(StopEvent.Correct(attempts, id)) }
        else { dimmed = dimmed + id; onEvent(StopEvent.Wrong(attempts, hint, numberLine)) }
    }
}

class SingleAnswerState(val dimmed: Set<String>, val done: Boolean, val answer: (String) -> Unit)

fun numberLineOf(stop: Stop): NumberLine? = when (stop) {
    is Stop.Sequence -> stop.numberLine; is Stop.Count -> stop.numberLine; is Stop.Compare -> stop.numberLine; else -> null
}

@Composable
fun PromptText(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.headlineMedium, color = Palette.ink, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.s16))
}

@Composable
fun SpeakButton(text: String, onEvent: (StopEvent) -> Unit, label: String = "Listen", modifier: Modifier = Modifier, width: Int = 260) {
    BigButton(label, onClick = { onEvent(StopEvent.Speak(text)) }, color = Palette.lavender, modifier = modifier.width(width.dp), emoji = "🔊", compact = true)
}

@Composable
fun SmallSpeakButton(text: String, onEvent: (StopEvent) -> Unit) {
    Box(
        Modifier.size(Dimens.minTarget).shadow(3.dp, RoundedCornerShape(20.dp)).background(Palette.lavender, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button) { onEvent(StopEvent.Speak(text)) }.semantics { contentDescription = "Say it" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Palette.ink) }
}

@Composable
fun DoneButton(text: String = "Done", enabled: Boolean = true, onClick: () -> Unit) {
    BigButton(text, onClick = onClick, emoji = "✅", enabled = enabled, modifier = Modifier.padding(top = Dimens.s16))
}

@Composable
fun CheckButton(enabled: Boolean, onClick: () -> Unit) {
    BigButton("Check", onClick = onClick, emoji = "👀", enabled = enabled, modifier = Modifier.padding(top = Dimens.s16))
}

@Composable
fun NumberChip(text: String, highlight: Boolean = false) {
    Box(
        Modifier.size(64.dp).shadow(3.dp, RoundedCornerShape(20.dp)).background(if (highlight) Palette.sun else Palette.cream, RoundedCornerShape(20.dp))
            .semantics { contentDescription = if (text == "?") "missing number" else text },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 26.sp, color = Palette.ink, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun BigCard(modifier: Modifier = Modifier, color: Color = Palette.cream, selected: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(color, RoundedCornerShape(Dimens.radiusCard))
            .border(if (selected) 4.dp else 0.dp, if (selected) Palette.sunDeep else Color.Transparent, RoundedCornerShape(Dimens.radiusCard))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(Dimens.s16),
    ) { content() }
}
