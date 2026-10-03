package quest.ui.stops

import androidx.compose.ui.platform.testTag
import quest.ui.design.TestTags
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
import kotlin.random.Random
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import quest.api.dto.Stop
import quest.api.dto.StopScoring
import quest.api.dto.Tile
import quest.ui.design.BigButton
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.Illustration
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
    val labels = LocalStopLabels.current
    val exam = LocalExamMode.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(prompt)
        if (pick != null) Text(labels.moreToSelect.replace("{n}", "$remaining"), style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft)
        Spacer(Modifier.height(Dimens.s16))
        TileGrid(options.map { it.spec() }, dimmed = dimmed, lit = lit, selected = selected, onTap = { tid ->
            if (done || tid in lit || tid in dimmed) return@TileGrid
            selected = if (tid in selected) selected - tid else if (remaining == null || selected.size < remaining) selected + tid else selected
        })
        CheckButton(enabled = selected.isNotEmpty() && !done) {
            val (right, wrong) = quest.api.dto.MultiAnswerLogic.check(selected, correctIds)
            if (exam) {
                // One Check, and what was selected is the answer: it stays selected, nothing lights or dims.
                val missed = correctIds.count { it !in selected }
                val perfect = wrong.isEmpty() && missed == 0
                done = true
                onEvent(StopEvent.Completed(if (perfect) StopScoring.byMistakes(0) else 0, answer = selected.joinToString(","), mistakes = wrong.size + missed, correct = perfect))
                return@CheckButton
            }
            lit = lit + right; dimmed = dimmed + wrong; mistakes += wrong.size; selected = emptySet()
            if (wrong.isNotEmpty()) onEvent(StopEvent.Speak(if (right.isNotEmpty()) labels.someRight else labels.notThose))
            if (lit.containsAll(correctIds)) { done = true; onEvent(StopEvent.Completed(StopScoring.byMistakes(mistakes), answer = lit.joinToString(","), mistakes = mistakes)) }
        }
    }
}

@Composable fun MultiSelectStop(stop: Stop.MultiSelect, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) = PickTiles(stop.id, stop.prompt, stop.options, stop.correctIds, stop.pick, onEvent, modifier)
@Composable fun SelectAllStop(stop: Stop.SelectAll, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) = PickTiles(stop.id, stop.prompt, stop.options, stop.correctIds, null, onEvent, modifier)

/** Two columns; tap one from each; matched pairs lock; stars by mistakes. */
@Composable
fun MatchStop(stop: Stop.Match, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    // Seeded from the stop id: the same stop always shuffles the same way, so a retry (or a screenshot test) is stable.
    val rights = rememberSaveable(stop.id) { stop.pairs.map { it.id }.shuffled(Random(stop.id.hashCode())) }
    var left by rememberSaveable(stop.id) { mutableStateOf<String?>(null) }
    var matched by rememberSaveable(stop.id) { mutableStateOf(setOf<String>()) }
    var mistakes by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var done by rememberSaveable(stop.id) { mutableStateOf(false) }
    val notAMatch = LocalStopLabels.current.notAMatch
    val exam = LocalExamMode.current
    // Exam: every pairing the student makes is kept as made (left id → right id), right or wrong.
    var paired by rememberSaveable(stop.id) { mutableStateOf(mapOf<String, String>()) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(stop.prompt); Spacer(Modifier.height(Dimens.s16))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s16)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                stop.pairs.forEach { p -> MatchTile(p.left, locked = p.id in matched || p.id in paired, selected = left == p.id, neutral = exam, tag = TestTags.matchLeft(p.id), onClick = { if (p.id !in matched && p.id !in paired) { left = p.id; onEvent(StopEvent.Speak(p.left.label ?: p.left.illustrationKey ?: "")) } }) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                rights.forEach { rid -> val p = stop.pairs.first { it.id == rid }
                    MatchTile(p.right, locked = p.id in matched || p.id in paired.values, selected = false, neutral = exam, tag = TestTags.matchRight(p.id), onClick = {
                        val l = left ?: return@MatchTile
                        if (exam) {
                            if (p.id in paired.values) return@MatchTile
                            paired = paired + (l to p.id); left = null
                            if (paired.size == stop.pairs.size) {
                                val wrong = paired.count { (a, b) -> a != b }
                                done = true
                                onEvent(StopEvent.Completed(if (wrong == 0) StopScoring.byMistakes(0) else 0, answer = paired.entries.joinToString(",") { "${it.key}=${it.value}" }, mistakes = wrong, correct = wrong == 0))
                            }
                            return@MatchTile
                        }
                        if (l == p.id) { matched = matched + p.id; left = null; if (matched.size == stop.pairs.size) { done = true; onEvent(StopEvent.Completed(StopScoring.byMistakes(mistakes), mistakes = mistakes)) } }
                        else { mistakes += 1; left = null; onEvent(StopEvent.Speak(notAMatch)) }
                    })
                }
            }
        }
    }
}

@Composable
private fun MatchTile(tile: Tile, locked: Boolean, selected: Boolean, tag: String, neutral: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(84.dp).alpha(if (locked) 0.55f else 1f)
            // In an exam a paired tile is only "used", so it takes the neutral tint rather than the success one.
            .background(if (locked && neutral) DashboardTokens.bgSubtle else if (locked) DashboardTokens.successBg else MaterialTheme.colorScheme.surface, RoundedCornerShape(DashboardTokens.radiusMd))
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else DashboardTokens.ruleControl, RoundedCornerShape(DashboardTokens.radiusMd))
            .clickable(enabled = !locked, role = Role.Button, onClick = onClick)
            .testTag(tag)
            .semantics { contentDescription = (tile.label ?: tile.illustrationKey ?: "") + if (locked) ", matched" else "" },
        contentAlignment = Alignment.Center,
    ) {
        if (tile.illustrationKey != null && tile.label == null) Illustration(tile.illustrationKey!!, 64.dp, corner = 14.dp)
        else Text(tile.label ?: "", style = MaterialTheme.typography.labelLarge, color = DashboardTokens.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
    }
}

/**
 * Order: tap the cards in order (the tapped card moves to the next slot; tap a slot to send it back), then
 * Check; wrong cards return to the pile. Stars by attempts. (Tap-to-place instead of drag — flagged.)
 */
@Composable
fun OrderStop(stop: Stop.Order, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    // Seeded from the stop id (see MatchStop) so the pile order is the same on every render.
    val shuffled = rememberSaveable(stop.id) { stop.items.map { it.id }.shuffled(Random(stop.id.hashCode())).let { if (it == stop.correctOrder) it.reversed() else it } }
    var placed by rememberSaveable(stop.id) { mutableStateOf(listOf<String>()) }
    var attempts by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var done by rememberSaveable(stop.id) { mutableStateOf(false) }
    var locked by rememberSaveable(stop.id) { mutableIntStateOf(0) }  // leading prefix confirmed correct
    val byId = stop.items.associateBy { it.id }
    val partlyRight = LocalStopLabels.current.orderPartlyRight
    val exam = LocalExamMode.current
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(stop.prompt); Spacer(Modifier.height(Dimens.s12))
        // slots
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            stop.correctOrder.indices.forEach { i ->
                val id = placed.getOrNull(i)
                Row(Modifier.fillMaxWidth().height(60.dp).background(if (i < locked) DashboardTokens.successBg else DashboardTokens.bgSubtle, RoundedCornerShape(DashboardTokens.radiusMd))
                    .clickable(enabled = id != null && i >= locked && !done) { placed = placed.filterIndexed { j, _ -> j != i } }
                    .padding(horizontal = 12.dp).semantics { contentDescription = "slot ${i + 1}: ${id?.let { byId[it]?.text } ?: "empty"}" }, verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleLarge, color = DashboardTokens.inkSoft); Spacer(Modifier.width(12.dp))
                    if (id != null) { byId[id]?.illustrationKey?.let { Illustration(it, 40.dp, corner = 10.dp); Spacer(Modifier.width(8.dp)) }
                        Text(byId[id]?.text ?: "", style = MaterialTheme.typography.labelLarge, color = DashboardTokens.ink, maxLines = 2) }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s16))
        // pile
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            shuffled.filter { it !in placed }.forEach { id ->
                Row(Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(DashboardTokens.radiusMd)).border(1.dp, DashboardTokens.ruleControl, RoundedCornerShape(DashboardTokens.radiusMd))
                    .clickable(enabled = !done) { placed = placed + id; onEvent(StopEvent.Speak(byId[id]?.text ?: "")) }.padding(horizontal = 12.dp).semantics { contentDescription = byId[id]?.text ?: "" }, verticalAlignment = Alignment.CenterVertically) {
                    byId[id]?.illustrationKey?.let { Illustration(it, 44.dp, corner = 10.dp); Spacer(Modifier.width(8.dp)) }
                    Text(byId[id]?.text ?: "", style = MaterialTheme.typography.labelLarge, color = DashboardTokens.ink, maxLines = 2)
                }
            }
        }
        CheckButton(enabled = placed.size == stop.correctOrder.size && !done) {
            attempts += 1
            val prefix = quest.api.dto.MultiAnswerLogic.lockedPrefix(placed, stop.correctOrder)
            if (exam) {
                // The order as placed is the answer; nothing is locked or sent back.
                val right = prefix == stop.correctOrder.size
                done = true
                onEvent(StopEvent.Completed(if (right) StopScoring.byAttempts(1) else 0, answer = placed.joinToString(","), mistakes = if (right) 0 else 1, correct = right))
                return@CheckButton
            }
            if (prefix == stop.correctOrder.size) { locked = prefix; done = true; onEvent(StopEvent.Completed(StopScoring.byAttempts(attempts), answer = placed.joinToString(","), mistakes = attempts - 1)) }
            else { locked = prefix; placed = placed.take(prefix); onEvent(StopEvent.Speak(partlyRight.replace("{n}", "$prefix"))) }
        }
    }
}

@Composable
fun TraceStop(stop: Stop.Trace, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val exam = LocalExamMode.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TraceCanvas(stop.text, onFinished = { coverage ->
            val stars = TraceScorer.stars(coverage)
            when {
                // An exam takes the tracing as it is, with no hint and no second go.
                exam -> onEvent(StopEvent.Completed(stars, answer = "coverage=$coverage", correct = stars > 0))
                stars == 0 -> onEvent(StopEvent.Speak(stop.hint))
                else -> onEvent(StopEvent.Completed(stars, answer = "coverage=$coverage"))
            }
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
            BigCard(color = if (cue.stage in told) DashboardTokens.successBg else MaterialTheme.colorScheme.surface, onClick = { told = told + cue.stage; onEvent(StopEvent.Speak("${cue.stage}. ${cue.cue}")) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    cue.illustrationKey?.let { Illustration(it, 64.dp, corner = 14.dp); Spacer(Modifier.width(Dimens.s12)) }
                    Column { Text(cue.stage.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = DashboardTokens.inkSoft); Text(cue.cue, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.ink) }
                }
            }
        }
        RecorderControls(recorder)
        DoneButton(text = LocalStopLabels.current.finished, enabled = told.size == stop.cues.size && !recorder.recording) { onEvent(StopEvent.Completed(StopScoring.OPEN, answer = "retold", recording = recorder.bytes)) }
    }
}

/** Open answer: speak (recorded), draw, or both. No wrong state. */
@Composable
fun OpenAnswerStop(stop: Stop.OpenAnswer, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val recorder = RecorderState(stop.id, enabled = stop.mode != "draw")
    var drawing by rememberSaveable(stop.id) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        PromptText(stop.prompt)
        if (stop.mode != "draw") { Text(LocalStopLabels.current.sayAnswer, style = MaterialTheme.typography.bodyLarge, color = DashboardTokens.inkSoft, textAlign = TextAlign.Center); RecorderControls(recorder) }
        if (stop.mode != "speak" && LocalDrawingEnabled.current) DrawingCanvas(onChange = { drawing = it })
        DoneButton(text = LocalStopLabels.current.finished, enabled = !recorder.recording) { onEvent(StopEvent.Completed(StopScoring.OPEN, answer = if (drawing != null) "drawn" else "spoken", recording = recorder.bytes, drawing = drawing)) }
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
    val labels = LocalStopLabels.current
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        BigButton(if (r.recording) labels.stopRecording else if (r.bytes == null) labels.record else labels.recordAgain, onClick = r.toggle, modifier = Modifier.width(170.dp), primary = r.recording, compact = true)
        if (r.bytes != null && !r.recording) BigButton(labels.play, onClick = r.play, modifier = Modifier.width(130.dp), primary = false, compact = true)
    }
}

/** Three questions in a row; stars = average of the three. */
@Composable
fun ExitTicketStop(stop: Stop.ExitTicket, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var index by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var stars by rememberSaveable(stop.id) { mutableStateOf(listOf<Int>()) }
    val q = stop.questions.getOrNull(index) ?: return
    val exam = LocalExamMode.current
    var wrongAnswers by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    // Exam: each of the three takes one answer and the ticket moves on in silence; the player hears only that the
    // whole stop is finished, so nothing between the questions says which were right.
    fun examNext(earned: Int, right: Boolean) {
        val all = stars + earned
        stars = all
        if (!right) wrongAnswers += 1
        if (index == stop.questions.lastIndex) onEvent(StopEvent.Completed(quest.api.dto.MultiAnswerLogic.exitTicketStars(all), mistakes = wrongAnswers, correct = wrongAnswers == 0))
        else { index += 1; onEvent(StopEvent.Speak(stop.questions[index].speak)) }
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = Dimens.s8)) {
            stop.questions.indices.forEach { i -> Box(Modifier.size(14.dp).background(if (i < index) DashboardTokens.success else if (i == index) MaterialTheme.colorScheme.primary else DashboardTokens.inkSoft.copy(alpha = 0.3f), RoundedCornerShape(7.dp))) }
        }
        StopContent(q, onEvent = { e ->
            when {
                // Each question reports its own answer first (the server scores the ticket by its questions), then moves on.
                exam && e is StopEvent.Correct -> { onEvent(StopEvent.QuestionAnswered(q.id, e.answer, true, StopScoring.singleAnswer(1))); examNext(StopScoring.singleAnswer(1), right = true) }
                exam && e is StopEvent.Wrong -> { onEvent(StopEvent.QuestionAnswered(q.id, e.answer, false, 0)); examNext(0, right = false) }
                exam && e is StopEvent.Completed -> { onEvent(StopEvent.QuestionAnswered(q.id, e.answer, e.correct, e.stars)); examNext(e.stars, right = e.correct) }
                e is StopEvent.Correct -> { val s = stars + StopScoring.singleAnswer(e.attempt); stars = s; onEvent(e); if (index == stop.questions.lastIndex) onEvent(StopEvent.Completed(quest.api.dto.MultiAnswerLogic.exitTicketStars(s))) else index += 1 }
                e is StopEvent.Completed -> { val s = stars + e.stars; stars = s; if (index == stop.questions.lastIndex) onEvent(StopEvent.Completed(quest.api.dto.MultiAnswerLogic.exitTicketStars(s))) else { index += 1; onEvent(StopEvent.Speak(stop.questions[index].speak)) } }
                else -> onEvent(e)
            }
        })
    }
}
