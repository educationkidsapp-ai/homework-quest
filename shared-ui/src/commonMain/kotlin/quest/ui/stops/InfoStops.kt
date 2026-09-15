package quest.ui.stops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import quest.api.dto.Stop
import quest.api.dto.StopScoring
import quest.ui.design.Dimens
import quest.ui.design.Illustration
import quest.ui.design.IllustrationGlyphs
import quest.ui.design.Palette

/** A page turned into a screen: picture, sentences highlighted as they are read, optional tap task. */
@Composable
fun ReadPageStop(stop: Stop.ReadPage, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var current by rememberSaveable(stop.id) { mutableIntStateOf(-1) }
    var reading by rememberSaveable(stop.id) { mutableStateOf(false) }
    var found by rememberSaveable(stop.id) { mutableStateOf(setOf<String>()) }
    var wrongTaps by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    val task = stop.tapTask
    val taskDone = task == null || found.containsAll(task.correctIds)

    LaunchedEffect(reading) {
        if (!reading) return@LaunchedEffect
        for (i in stop.sentences.indices) {
            current = i
            onEvent(StopEvent.Speak(stop.sentences[i]))
            delay((stop.sentences[i].split(' ').size * 480L + 700L))
        }
        reading = false
        if (task != null && !taskDone) onEvent(StopEvent.Speak(task.prompt))
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // picture area with optional hotspots
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Dimens.s16).aspectRatio(1.5f).shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(IllustrationGlyphs.tint(stop.illustrationKey ?: "book"), RoundedCornerShape(Dimens.radiusCard))
            .semantics { contentDescription = stop.pictureDescription ?: "picture" }) {
            val w = maxWidth; val h = maxHeight
            val picture by rememberStopImage(stop.imageId ?: stop.pageImageId)
            val bmp = picture
            if (bmp != null) androidx.compose.foundation.Image(bmp, stop.pictureDescription, Modifier.matchParentSize().clip(RoundedCornerShape(Dimens.radiusCard)), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
            if (task == null) {
                if (bmp == null) Text(IllustrationGlyphs.glyph(stop.illustrationKey ?: "book"), fontSize = 96.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                task.hotspots.forEach { hs ->
                    val ok = hs.id in found
                    Box(
                        Modifier.offset(w * hs.x, h * hs.y).size(w * hs.w, h * hs.h)
                            .background(if (ok) Palette.mint else Color.White.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                            .clickable(role = Role.Button) {
                                if (hs.id in task.correctIds) { if (hs.id !in found) { found = found + hs.id; onEvent(StopEvent.Speak(hs.label)) } }
                                else { wrongTaps += 1; onEvent(StopEvent.Speak("Not that one. ${task.prompt}")) }
                            }
                            .semantics { contentDescription = hs.label + if (ok) ", found" else "" },
                        contentAlignment = Alignment.Center,
                    ) { Text(IllustrationGlyphs.glyph(hs.label.removeSuffix("s")), fontSize = 34.sp) }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s16))
        stop.sentences.forEachIndexed { i, s ->
            Text(
                s, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.s24, vertical = 2.dp)
                    .background(if (i == current) Palette.sun.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable { current = i; onEvent(StopEvent.Speak(s)) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(Dimens.s12))
        if (task != null) Text(task.prompt + "  (${found.size}/${task.correctIds.size})", style = MaterialTheme.typography.labelLarge, color = Palette.inkSoft, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            SpeakButton("", { if (!reading) reading = true }, label = if (reading) "Reading…" else "Read to me")
        }
        DoneButton(enabled = taskDone && !reading) { onEvent(StopEvent.Completed(if (task == null) StopScoring.INFO else StopScoring.byMistakes(wrongTaps), mistakes = wrongTaps)) }
    }
}

/** Six tappable cards: Title, Genre, Characters, Setting, Plot, Problem. Each speaks its definition and answer. */
@Composable
fun StoryPiecesStop(stop: Stop.StoryPieces, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var opened by rememberSaveable(stop.id) { mutableStateOf(setOf<String>()) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        stop.cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
                row.forEach { card ->
                    val open = card.piece in opened
                    Box(
                        Modifier.weight(1f).height(120.dp).shadow(4.dp, RoundedCornerShape(Dimens.radiusTile)).background(if (open) Palette.mint else Palette.cream, RoundedCornerShape(Dimens.radiusTile))
                            .clickable(role = Role.Button) { opened = opened + card.piece; onEvent(StopEvent.Speak("${card.piece}. ${card.definition} ${card.answer}")) }
                            .padding(Dimens.s8).semantics { contentDescription = "${card.piece}: ${card.answer}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(pieceEmoji(card.piece), fontSize = 26.sp)
                            Text(card.piece.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge, color = Palette.ink)
                            if (open) Text(card.answer, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 16.sp), color = Palette.ink, textAlign = TextAlign.Center, maxLines = 3)
                        }
                    }
                }
            }
        }
        DoneButton(enabled = opened.size == stop.cards.size) { onEvent(StopEvent.Completed(StopScoring.INFO)) }
    }
}

private fun pieceEmoji(piece: String) = when (piece) { "title" -> "📕"; "genre" -> "🎭"; "characters" -> "👪"; "setting" -> "🏠"; "plot" -> "🎬"; else -> "❗" }

/** New words, one card each: picture, meaning, the sentence from the source. */
@Composable
fun WordCardsStop(stop: Stop.WordCards, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var index by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    var seen by rememberSaveable(stop.id) { mutableStateOf(setOf(0)) }
    val card = stop.words[index]
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally) {
        BigCard(onClick = { onEvent(StopEvent.Speak("${card.word}. ${card.meaning} ${card.sentence}")) }) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Illustration(card.illustrationKey, 120.dp)
                Spacer(Modifier.height(Dimens.s8))
                Text(card.word, style = MaterialTheme.typography.displayLarge, color = Palette.ink)
                Text(card.meaning, style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Dimens.s8))
                Text("“${card.sentence}”", style = MaterialTheme.typography.bodyLarge, color = Palette.ink, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(Dimens.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
            stop.words.indices.forEach { i -> Box(Modifier.size(14.dp).background(if (i in seen) Palette.sunDeep else Palette.inkSoft.copy(alpha = 0.3f), CircleShape)) }
        }
        Spacer(Modifier.height(Dimens.s12))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            SpeakButton("${card.word}. ${card.meaning}", onEvent, modifier = Modifier.width(150.dp))
            if (index < stop.words.lastIndex) quest.ui.design.BigButton("Next", onClick = { index += 1; seen = seen + index }, modifier = Modifier.width(150.dp), emoji = "➡️")
        }
        DoneButton(enabled = seen.size == stop.words.size) { onEvent(StopEvent.Completed(StopScoring.INFO)) }
    }
}

/** 3–4 physical actions, one big card each. */
@Composable
fun MoveStop(stop: Stop.Move, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var done by rememberSaveable(stop.id) { mutableStateOf(setOf<Int>()) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        stop.actions.forEachIndexed { i, a ->
            BigCard(color = if (i in done) Palette.mint else Palette.cream, onClick = { done = done + i; onEvent(StopEvent.Speak(a.text)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.emoji, fontSize = 40.sp); Spacer(Modifier.width(Dimens.s16))
                    Text(a.text, style = MaterialTheme.typography.bodyLarge, color = Palette.ink, modifier = Modifier.weight(1f))
                    if (i in done) Text("✓", fontSize = 28.sp, color = Palette.ink)
                }
            }
        }
        DoneButton(enabled = done.size == stop.actions.size) { onEvent(StopEvent.Completed(StopScoring.INFO)) }
    }
}

/** A math explanation with worked examples revealed one at a time. */
@Composable
fun ExplainStop(stop: Stop.Explain, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    var revealed by rememberSaveable(stop.id) { mutableIntStateOf(0) }
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.s12)) {
        stop.workedExamples.take(revealed + 1).forEachIndexed { i, ex ->
            BigCard(onClick = { onEvent(StopEvent.Speak("${ex.prompt}. ${ex.steps.joinToString(". ")}. ${ex.answer}.")) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ex.prompt, style = MaterialTheme.typography.headlineMedium, color = Palette.ink)
                    ex.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = Palette.inkSoft) }
                    Text("= ${ex.answer}", style = MaterialTheme.typography.titleLarge, color = Palette.ink)
                }
            }
        }
        if (revealed < stop.workedExamples.lastIndex) quest.ui.design.BigButton("Show me another", onClick = { revealed += 1 }, color = Palette.lavender, emoji = "👀")
        else DoneButton(text = "Let's go") { onEvent(StopEvent.Completed(StopScoring.INFO)) }
    }
}
