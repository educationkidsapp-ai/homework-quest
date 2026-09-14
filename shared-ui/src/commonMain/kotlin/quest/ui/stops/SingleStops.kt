package quest.ui.stops

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.Stop
import quest.ui.design.Dimens
import quest.ui.design.Illustration
import quest.ui.design.IllustrationGlyphs
import quest.ui.design.Palette

@Composable
fun ChoiceStop(stop: Stop.Choice, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PromptText(stop.question); Spacer(Modifier.height(Dimens.s24))
        TileGrid(stop.options.map { it.spec() }, onTap = s.answer, dimmed = s.dimmed)
    }
}

/** Statement spoken; thumbs-up / thumbs-down tiles. */
@Composable
fun TrueFalseStop(stop: Stop.TrueFalse, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigCard(Modifier.padding(horizontal = Dimens.s16), onClick = { onEvent(StopEvent.Speak(stop.statement)) }) {
            Text(stop.statement, style = MaterialTheme.typography.headlineMedium, color = Palette.ink, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(Dimens.s24))
        TileGrid(listOf(TileSpec(Stop.TrueFalse.TRUE_ID, "👍 True"), TileSpec(Stop.TrueFalse.FALSE_ID, "👎 False")), onTap = s.answer, dimmed = s.dimmed, fontSize = 26)
    }
}

@Composable
fun SequenceStop(stop: Stop.Sequence, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().wrapContentWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            stop.chips.forEach { v -> NumberChip(v?.toString() ?: "?", highlight = v == null) }
        }
        Spacer(Modifier.height(Dimens.s32))
        TileGrid(stop.options.map { TileSpec(it.id, it.label) }, onTap = s.answer, dimmed = s.dimmed)
    }
}

@Composable
fun CountStop(stop: Stop.Count, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stop.groupSizes.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { size ->
                    Box(Modifier.background(IllustrationGlyphs.tint(stop.objectKey), RoundedCornerShape(18.dp)).padding(8.dp).semantics { contentDescription = "group of $size ${stop.objectKey}" }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(size) { Text(IllustrationGlyphs.glyph(stop.objectKey), fontSize = 30.sp) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s16))
        TileGrid(stop.options.map { TileSpec(it.id, it.label) }, onTap = s.answer, dimmed = s.dimmed)
    }
}

@Composable
fun CompareStop(stop: Stop.Compare, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            BigNumberCard(stop.left.toString()); Spacer(Modifier.width(16.dp)); NumberChip("?", highlight = true); Spacer(Modifier.width(16.dp)); BigNumberCard(stop.right.toString())
        }
        Spacer(Modifier.height(Dimens.s32))
        TileGrid(stop.options.map { TileSpec(it.id, it.label) }, onTap = s.answer, dimmed = s.dimmed, fontSize = 44)
    }
}

@Composable
private fun BigNumberCard(text: String) {
    Box(
        Modifier.size(110.dp, 130.dp).shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream, RoundedCornerShape(Dimens.radiusCard)).semantics { contentDescription = "number $text" },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 52.sp, color = Palette.ink, style = MaterialTheme.typography.displayLarge) }
}

@Composable
fun SoundStop(stop: Stop.Sound, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Illustration(stop.illustrationKey, 180.dp)
        Spacer(Modifier.height(Dimens.s32))
        TileGrid(stop.options.map { TileSpec(it.id, it.label) }, onTap = s.answer, dimmed = s.dimmed)
    }
}

@Composable
fun WordStop(stop: Stop.Word, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SpeakButton(stop.spokenWord, onEvent)
        Spacer(Modifier.height(Dimens.s32))
        TileGrid(stop.options.map { TileSpec(it.id, it.label) }, onTap = s.answer, dimmed = s.dimmed)
    }
}

@Composable
fun ReadTapStop(stop: Stop.ReadTap, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    val s = rememberSingleAnswer(stop, onEvent)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream, RoundedCornerShape(Dimens.radiusCard)).padding(horizontal = 28.dp, vertical = 16.dp)) {
                Text(stop.word, fontSize = 44.sp, color = Palette.ink, style = MaterialTheme.typography.displayLarge)
            }
            Spacer(Modifier.width(16.dp)); SmallSpeakButton(stop.word, onEvent)
        }
        Spacer(Modifier.height(Dimens.s32))
        TileGrid(stop.options.map { TileSpec(it.id, picture = it.illustrationKey) }, onTap = s.answer, dimmed = s.dimmed)
    }
}

/** Sentence frame with a gap: word tiles (Level 2) or free tracing (Level 3). */
@Composable
fun WriteSentenceStop(stop: Stop.WriteSentence, onEvent: (StopEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigCard(Modifier.padding(horizontal = Dimens.s16), onClick = { onEvent(StopEvent.Speak(stop.frame.replace("___", "blank"))) }) {
            Text(stop.frame, style = MaterialTheme.typography.headlineMedium, color = Palette.ink, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(Dimens.s24))
        val options = stop.options
        if (!stop.free && options != null) {
            val s = rememberSingleAnswer(stop.id, stop.answer, "Read the sentence again. Which word makes sense?", null, onEvent)
            TileGrid(options.map { TileSpec(it, it) }, onTap = s.answer, dimmed = s.dimmed)
        } else {
            quest.ui.trace.TraceCanvas(stop.answer, onFinished = { coverage -> onEvent(StopEvent.Completed(quest.ui.trace.TraceScorer.stars(coverage).coerceAtLeast(1), answer = stop.answer)) })
        }
    }
}
