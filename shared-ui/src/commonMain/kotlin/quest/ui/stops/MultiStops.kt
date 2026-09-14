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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.Stop
import quest.api.dto.StopScoring
import quest.api.dto.Tile
import quest.ui.design.BigButton
import quest.ui.design.Dimens
import quest.ui.design.Illustration
import quest.ui.design.Palette
import quest.ui.trace.TraceCanvas
import quest.ui.trace.TraceScorer

/**
 * multiSelect / selectAll: tap tiles, Check; correct picks stay lit, wrong ones dim; the child keeps going
 * until every correct tile is lit. Stars by mistakes (0 → 3, ≤ 2 → 2, else 1). Correct picks never reset.
 */
@Composable
private fun PickTiles(id: String, prompt: String, options: List<Tile>, correctIds: List<String>, pick: Int?, onEvent: (StopEvent) -> Unit, modifier: Modifier) {
    var selected by rememberSaveable(id) { mutableStateOf(setOf<String>()) }
    var lit by rememberSaveable(id) { mutableStateOf(setOf<String>()) }
    var dimmed by rememberSaveable(id) { mutableStateOf(setOf<String>()) }
    var mistakes by rememberSaveable(id) { mutableIntStateOf(0) }
    var done by rememberSaveable(id) { mutableStateOf(false) }
    val remaining = pick?.let { it - lit.size }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(prompt)
        if (pick != null) Text("$remaining more to tap", style = MaterialTheme.typography.labelLarge, color = Palette.inkSoft)
        Spacer(Modifier.height(Dimens.s16))
        TileGrid(options.map { it.spec() }, dimmed = dimmed, lit = lit, selected = selected, onTap = { tid ->
            if (done || tid in lit || tid in dimmed) return@TileGrid
            selected = if (tid in selected) selected - tid else if (remaining == null || selected.size < remaining) selected + tid else selected
        })
        CheckButton(enabled = selected.isNotEmpty() && !done) {
            val (right, wrong) = quest.api.dto.MultiAnswerLogic.check(selected, correctIds)
            lit = lit + right; dimmed = dimmed + wrong; mistakes += wrong.size; selected = emptySet()
            if (wrong.isNotEmpty()) onEvent(StopEvent.Speak(if (right.isNotEmpty()) "Some are right! Keep going." else "Not those. Try again!"))
            if (lit.containsAll(correctIds)) { done = true; onEvent(StopEvent.Completed(StopScoring.byMistakes(mistakes), answer = lit.joinToString(","), mistakes = mistakes)) }
        }
    }
}

@Composable fun MultiSelectStop(stop: Stop.MultiSelect, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) = PickTiles(stop.id, stop.prompt, stop.options, stop.correctIds, stop.pick, onEvent, modifier)
@Composable fun SelectAllStop(stop: Stop.SelectAll, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) = PickTiles(stop.id, stop.prompt, stop.options, stop.correctIds, null, onEvent, modifier)

/** Two columns; tap one from each; matched pairs lock; stars by mistakes. */
@Composable
fun MatchStop(stop: Stop.Match, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val rights = rememberSaveable(stop.id) { stop.pairs.map { it.id }.shuffled() }
    var left by rememberSaveable(stop.id) { mutableStateOf<String?>(null) }
    var matched by rememberSaveable(stop.id) { mutableStateOf(setOf<String>()) }
    var mistakes by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var done by rememberSaveable(stop.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(stop.prompt); Spacer(Modifier.height(Dimens.s16))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                stop.pairs.forEach { p -> MatchTile(p.left, locked = p.id in matched, selected = left == p.id, onClick = { if (p.id !in matched) { left = p.id; onEvent(StopEvent.Speak(p.left.label ?: p.left.illustrationKey ?: "")) } }) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                rights.forEach { rid -> val p = stop.pairs.first { it.id == rid }
                    MatchTile(p.right, locked = p.id in matched, selected = false, onClick = {
                        val l = left ?: return@MatchTile
                        if (l == p.id) { matched = matched + p.id; left = null; if (matched.size == stop.pairs.size) { done = true; onEvent(StopEvent.Completed(StopScoring.byMistakes(mistakes), mistakes = mistakes)) } }
                        else { mistakes += 1; left = null; onEvent(StopEvent.Speak("Not a match. Try again!")) }
                    })
                }
            }
        }
    }
}

@Composable
private fun MatchTile(tile: Tile, locked: Boolean, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(84.dp).alpha(if (locked) 0.55f else 1f).shadow(if (locked) 0.dp else 4.dp, RoundedCornerShape(Dimens.radiusTile))
            .background(if (locked) Palette.mint else Palette.cream, RoundedCornerShape(Dimens.radiusTile))
            .border(if (selected) 4.dp else 0.dp, if (selected) Palette.sunDeep else Color.Transparent, RoundedCornerShape(Dimens.radiusTile))
            .clickable(enabled = !locked, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = (tile.label ?: tile.illustrationKey ?: "") + if (locked) ", matched" else "" },
        contentAlignment = Alignment.Center,
    ) {
        if (tile.illustrationKey != null && tile.label == null) Illustration(tile.illustrationKey!!, 64.dp, corner = 14.dp)
        else Text(tile.label ?: "", style = MaterialTheme.typography.labelLarge, color = Palette.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
    }
}

/**
 * Order: tap the cards in order (the tapped card moves to the next slot; tap a slot to send it back), then
 * Check; wrong cards return to the pile. Stars by attempts. (Tap-to-place instead of drag — flagged.)
 */
@Composable
fun OrderStop(stop: Stop.Order, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val shuffled = rememberSaveable(stop.id) { stop.items.map { it.id }.shuffled().let { if (it == stop.correctOrder) it.reversed() else it } }
    var placed by rememberSaveable(stop.id) { mutableStateOf(listOf<String>()) }
    var attempts by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var done by rememberSaveable(stop.id) { mutableStateOf(false) }
    var locked by rememberSaveable(stop.id) { mutableIntStateOf(0) }  // leading prefix confirmed correct
    val byId = stop.items.associateBy { it.id }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(stop.prompt); Spacer(Modifier.height(Dimens.s12))
        // slots
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            stop.correctOrder.indices.forEach { i ->
                val id = placed.getOrNull(i)
                Row(Modifier.fillMaxWidth().height(60.dp).background(if (i < locked) Palette.mint else Palette.sand.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .clickable(enabled = id != null && i >= locked && !done) { placed = placed.filterIndexed { j, _ -> j != i } }
                    .padding(horizontal = 12.dp).semantics { contentDescription = "slot ${i + 1}: ${id?.let { byId[it]?.text } ?: "empty"}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = Palette.inkSoft); Spacer(Modifier.width(12.dp))
                    if (id != null) { byId[id]?.illustrationKey?.let { Illustration(it, 40.dp, corner = 10.dp); Spacer(Modifier.width(8.dp)) }
                        Text(byId[id]?.text ?: "", style = MaterialTheme.typography.labelLarge, color = Palette.ink, maxLines = 2) }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s16))
        // pile
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            shuffled.filter { it !in placed }.forEach { id ->
                Row(Modifier.fillMaxWidth().height(64.dp).shadow(3.dp, RoundedCornerShape(16.dp)).background(Palette.cream, RoundedCornerShape(16.dp))
                    .clickable(enabled = !done) { placed = placed + id; onEvent(StopEvent.Speak(byId[id]?.text ?: "")) }.padding(horizontal = 12.dp).semantics { contentDescription = byId[id]?.text ?: "" }, verticalAlignment = Alignment.CenterVertically) {
                    byId[id]?.illustrationKey?.let { Illustration(it, 44.dp, corner = 10.dp); Spacer(Modifier.width(8.dp)) }
                    Text(byId[id]?.text ?: "", style = MaterialTheme.typography.labelLarge, color = Palette.ink, maxLines = 2)
                }
            }
        }
        CheckButton(enabled = placed.size == stop.correctOrder.size && !done) {
            attempts += 1
            val prefix = quest.api.dto.MultiAnswerLogic.lockedPrefix(placed, stop.correctOrder)
            if (prefix == stop.correctOrder.size) { locked = prefix; done = true; onEvent(StopEvent.Completed(StopScoring.byAttempts(attempts), answer = placed.joinToString(","), mistakes = attempts - 1)) }
            else { locked = prefix; placed = placed.take(prefix); onEvent(StopEvent.Speak("Almost! The first $prefix are right. Try the rest again.")) }
        }
    }
}

@Composable
fun TraceStop(stop: Stop.Trace, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TraceCanvas(stop.text, onFinished = { coverage ->
            val stars = TraceScorer.stars(coverage)
            if (stars == 0) onEvent(StopEvent.Speak(stop.hint)) else onEvent(StopEvent.Completed(stars, answer = "coverage=$coverage"))
        })
    }
}

/** Retell: the child taps each cue picture as they tell the story, optionally recording audio for the parent. */
@Composable
fun RetellStop(stop: Stop.Retell, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var told by rememberSaveable(stop.id) { mutableStateOf(setOf<String>()) }
    val recorder = RecorderState(stop.id, enabled = stop.record)
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        PromptText(stop.prompt)
        stop.cues.forEach { cue ->
            BigCard(color = if (cue.stage in told) Palette.mint else Palette.cream, onClick = { told = told + cue.stage; onEvent(StopEvent.Speak("${cue.stage}. ${cue.cue}")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cue.illustrationKey?.let { Illustration(it, 64.dp, corner = 14.dp); Spacer(Modifier.width(Dimens.s12)) }
                    Column { Text(cue.stage.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = Palette.inkSoft); Text(cue.cue, style = MaterialTheme.typography.bodyLarge, color = Palette.ink) }
                }
            }
        }
        RecorderControls(recorder)
        DoneButton(text = "I told it!", enabled = told.size == stop.cues.size && !recorder.recording) { onEvent(StopEvent.Completed(StopScoring.OPEN, answer = "retold", recording = recorder.bytes)) }
    }
}

/** Open answer: speak (recorded), draw, or both. No wrong state; Pip celebrates the attempt. */
@Composable
fun OpenAnswerStop(stop: Stop.OpenAnswer, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val recorder = RecorderState(stop.id, enabled = stop.mode != "draw")
    var drawing by rememberSaveable(stop.id) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        PromptText(stop.prompt)
        if (stop.mode != "draw") { Text("🗣️", fontSize = 56.sp); Text("Say your idea out loud.", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft, textAlign = TextAlign.Center); RecorderControls(recorder) }
        if (stop.mode != "speak") DrawingCanvas(onChange = { drawing = it })
        DoneButton(text = "I'm done!", enabled = !recorder.recording) { onEvent(StopEvent.Completed(StopScoring.OPEN, answer = if (drawing != null) "drawn" else "spoken", recording = recorder.bytes, drawing = drawing)) }
    }
}

class RecorderHandle(val recording: Boolean, val bytes: ByteArray?, val available: Boolean, val toggle: () -> Unit, val play: () -> Unit)

@Composable
private fun RecorderState(key: String, enabled: Boolean): RecorderHandle {
    val media = LocalStopMedia.current
    var recording by rememberSaveable(key) { mutableStateOf(false) }
    var bytes by remember(key) { mutableStateOf<ByteArray?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return RecorderHandle(recording, bytes, enabled && media.canRecord,
        toggle = { scope.launch { if (recording) { bytes = media.stopRecording(); recording = false } else if (media.startRecording()) recording = true } },
        play = { bytes?.let { b -> scope.launch { media.play(b) } } })
}

@Composable
private fun RecorderControls(r: RecorderHandle) {
    if (!r.available) return
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        BigButton(if (r.recording) "Stop" else if (r.bytes == null) "Record" else "Record again", onClick = r.toggle, modifier = Modifier.width(170.dp), color = if (r.recording) Palette.coral else Palette.lavender, emoji = if (r.recording) "⏹️" else "🎙️", compact = true)
        if (r.bytes != null && !r.recording) BigButton("Play", onClick = r.play, modifier = Modifier.width(130.dp), color = Palette.cream, emoji = "▶️", compact = true)
    }
}

/** Three questions in a row; stars = average of the three. */
@Composable
fun ExitTicketStop(stop: Stop.ExitTicket, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var index by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var stars by rememberSaveable(stop.id) { mutableStateOf(listOf<Int>()) }
    val q = stop.questions.getOrNull(index) ?: return
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = Dimens.s8)) {
            stop.questions.indices.forEach { i -> Box(Modifier.size(14.dp).background(if (i < index) Palette.mint else if (i == index) Palette.sun else Palette.inkSoft.copy(alpha = 0.3f), RoundedCornerShape(7.dp))) }
        }
        StopContent(q, onEvent = { e ->
            when (e) {
                is StopEvent.Correct -> { val s = stars + StopScoring.singleAnswer(e.attempt); stars = s; onEvent(e); if (index == stop.questions.lastIndex) onEvent(StopEvent.Completed(quest.api.dto.MultiAnswerLogic.exitTicketStars(s))) else index += 1 }
                is StopEvent.Completed -> { val s = stars + e.stars; stars = s; if (index == stop.questions.lastIndex) onEvent(StopEvent.Completed(quest.api.dto.MultiAnswerLogic.exitTicketStars(s))) else { index += 1; onEvent(StopEvent.Speak(stop.questions[index].speak)) } }
                else -> onEvent(e)
            }
        })
    }
}
