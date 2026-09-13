package quest.feature.practice.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import quest.api.dto.Question
import quest.core.design.AnswerTile
import quest.core.design.BigButton
import quest.core.design.Dimens
import quest.core.design.Illustration
import quest.core.design.IllustrationGlyphs
import quest.core.design.Palette

/** The prompt area for each question type (docs/design.md screens 4–10). */
@Composable
fun QuestionPrompt(question: Question, onSpeak: (String) -> Unit, modifier: Modifier = Modifier) {
    when (question) {
        is Question.Sequence -> SequencePrompt(question, modifier)
        is Question.Count -> CountPrompt(question, modifier)
        is Question.Compare -> ComparePrompt(question, modifier)
        is Question.Sound -> Box(modifier, contentAlignment = Alignment.Center) { Illustration(question.illustrationKey, 180.dp) }
        is Question.Word -> WordPrompt(question, onSpeak, modifier)
        is Question.ReadTap -> ReadTapPrompt(question, onSpeak, modifier)
        is Question.Trace -> Unit // drawn by TraceCanvas
    }
}

@Composable
private fun NumberChip(text: String, highlight: Boolean = false) {
    Box(
        Modifier.size(72.dp).shadow(3.dp, RoundedCornerShape(20.dp))
            .background(if (highlight) Palette.sun else Palette.cream, RoundedCornerShape(20.dp))
            .semantics { contentDescription = if (text == "?") "missing number" else text },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 30.sp, color = Palette.ink, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun SequencePrompt(q: Question.Sequence, modifier: Modifier) {
    Row(modifier.fillMaxWidth().wrapContentWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        q.chips.forEach { v -> NumberChip(v?.toString() ?: "?", highlight = v == null) }
    }
}

@Composable
private fun CountPrompt(q: Question.Count, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val rows = q.groupSizes.chunked(3)
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { size ->
                    Box(
                        Modifier.background(IllustrationGlyphs.tint(q.objectKey), RoundedCornerShape(18.dp)).padding(8.dp)
                            .semantics { contentDescription = "group of $size ${q.objectKey}" },
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            repeat(size) { Text(IllustrationGlyphs.glyph(q.objectKey), fontSize = 30.sp) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparePrompt(q: Question.Compare, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        BigNumberCard(q.left.toString())
        Spacer(Modifier.width(16.dp))
        NumberChip("?", highlight = true)
        Spacer(Modifier.width(16.dp))
        BigNumberCard(q.right.toString())
    }
}

@Composable
private fun BigNumberCard(text: String) {
    Box(
        Modifier.size(110.dp, 130.dp).shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream, RoundedCornerShape(Dimens.radiusCard))
            .semantics { contentDescription = "number $text" },
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 52.sp, color = Palette.ink, style = MaterialTheme.typography.displayLarge) }
}

@Composable
fun ListenButton(text: String, onSpeak: (String) -> Unit, label: String = "Listen") {
    BigButton(label, onClick = { onSpeak(text) }, color = Palette.lavender, modifier = Modifier.width(220.dp), emoji = "🔊")
}

@Composable
private fun WordPrompt(q: Question.Word, onSpeak: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ListenButton(q.spokenWord, onSpeak)
    }
}

@Composable
private fun ReadTapPrompt(q: Question.ReadTap, onSpeak: (String) -> Unit, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.shadow(4.dp, RoundedCornerShape(Dimens.radiusCard)).background(Palette.cream, RoundedCornerShape(Dimens.radiusCard)).padding(horizontal = 28.dp, vertical = 16.dp),
        ) { Text(q.word, fontSize = 44.sp, color = Palette.ink, style = MaterialTheme.typography.displayLarge) }
        Spacer(Modifier.width(16.dp))
        Box(
            Modifier.size(Dimens.minTarget).shadow(3.dp, RoundedCornerShape(20.dp)).background(Palette.lavender, RoundedCornerShape(20.dp))
                .semantics { contentDescription = "Say the word" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(Dimens.minTarget).clickable { onSpeak(q.word) }, contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Palette.ink)
            }
        }
    }
}

/** Answer tiles: 2-up grid of 176×100 tiles with 12 dp gaps. */
@Composable
fun AnswerTiles(question: Question, dimmed: Set<String>, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val items: List<Pair<String, @Composable () -> Unit>> = when (question) {
        is Question.ReadTap -> question.options.map { o -> o.id to @Composable { Illustration(o.illustrationKey, 88.dp, corner = 18.dp) } }
        is Question.Trace -> emptyList()
        else -> emptyList()
    }
    val labels: List<Pair<String, String>> = when (question) {
        is Question.Sequence -> question.options.map { it.id to it.label }
        is Question.Count -> question.options.map { it.id to it.label }
        is Question.Compare -> question.options.map { it.id to it.label }
        is Question.Sound -> question.options.map { it.id to it.label }
        is Question.Word -> question.options.map { it.id to it.label }
        is Question.ReadTap -> question.options.map { it.id to it.illustrationKey }
        is Question.Trace -> emptyList()
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        labels.chunked(2).forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Spacer(Modifier.height(Dimens.tileGap))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.tileGap)) {
                row.forEach { (id, label) ->
                    val picture = items.firstOrNull { it.first == id }?.second
                    AnswerTile(
                        label = label,
                        onClick = { onAnswer(id) },
                        dimmed = id in dimmed,
                        fontSize = if (question is Question.Compare) 44 else if (label.length > 5) 28 else 36,
                        content = picture,
                    )
                }
            }
        }
    }
}
