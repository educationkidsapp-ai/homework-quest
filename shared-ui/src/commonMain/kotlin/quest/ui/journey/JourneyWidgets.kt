package quest.ui.journey

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import quest.api.dto.Stop
import quest.ui.design.DashboardCard
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens
import quest.ui.design.StarRow

/**
 * The words the lesson overview draws: the step kinds, the three levels and the certificate. English by default; the
 * app provides the parent's language through [LocalJourneyLabels]. `{n}` is a count, `{name}` a level's name.
 */
data class JourneyLabels(
    val step: String = "Step {n}",
    val level: String = "Level {n}",
    val levelNames: Map<Int, String> = mapOf(1 to "Core", 2 to "Extended", 3 to "Advanced"),
    val locked: String = "Locked",
    val completed: String = "Completed",
    val current: String = "Next",
    val certificate: String = "Certificate of completion",
    val certificateFor: String = "Awarded to",
    val certificateLesson: String = "for completing",
    val studentFallback: String = "Student",
    val kindRead: String = "Reading",
    val kindStory: String = "Story elements",
    val kindWords: String = "Vocabulary",
    val kindActivity: String = "Activity",
    val kindExplain: String = "Explanation",
    val kindQuestion: String = "Question",
    val kindSelect: String = "Multiple selection",
    val kindMatch: String = "Matching",
    val kindOrder: String = "Ordering",
    val kindTrace: String = "Handwriting",
    val kindRetell: String = "Retelling",
    val kindOpen: String = "Open answer",
    val kindSentence: String = "Sentence completion",
    val kindReview: String = "Review questions",
) {
    fun kind(stop: Stop): String = when (stop) {
        is Stop.ReadPage -> kindRead; is Stop.StoryPieces -> kindStory; is Stop.WordCards -> kindWords; is Stop.Move -> kindActivity; is Stop.Explain -> kindExplain
        is Stop.Choice, is Stop.TrueFalse, is Stop.Sequence, is Stop.Count, is Stop.Compare, is Stop.Sound, is Stop.Word, is Stop.ReadTap -> kindQuestion
        is Stop.MultiSelect, is Stop.SelectAll -> kindSelect; is Stop.Match -> kindMatch; is Stop.Order -> kindOrder; is Stop.Trace -> kindTrace
        is Stop.Retell -> kindRetell; is Stop.OpenAnswer -> kindOpen; is Stop.WriteSentence -> kindSentence; is Stop.ExitTicket -> kindReview
    }
}

val LocalJourneyLabels = staticCompositionLocalOf { JourneyLabels() }

enum class NodeState { DONE, CURRENT, LOCKED }

/**
 * The steps of a lesson as a list of cards — number, title, kind, and the stars of a finished step. A locked step is
 * drawn but not tappable.
 */
@Composable
fun StepList(stops: List<Stop>, states: List<NodeState>, stars: List<Int?>, onTap: (Int) -> Unit, modifier: Modifier = Modifier) {
    val labels = LocalJourneyLabels.current
    Column(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), verticalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        stops.forEachIndexed { i, stop ->
            val state = states.getOrElse(i) { NodeState.LOCKED }
            val stateWord = when (state) { NodeState.DONE -> labels.completed; NodeState.CURRENT -> labels.current; NodeState.LOCKED -> labels.locked }
            DashboardCard(
                modifier = Modifier.heightIn(min = Dimens.minTarget).alpha(if (state == NodeState.LOCKED) 0.6f else 1f)
                    .semantics { contentDescription = "${labels.step.replace("{n}", "${i + 1}")}: ${stop.title}, $stateWord" },
                onClick = if (state != NodeState.LOCKED) ({ onTap(i) }) else null,
                borderColor = if (state == NodeState.CURRENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).background(
                            when (state) { NodeState.DONE -> DashboardTokens.successBg; NodeState.CURRENT -> MaterialTheme.colorScheme.primaryContainer; NodeState.LOCKED -> DashboardTokens.bgSubtle },
                            RoundedCornerShape(DashboardTokens.radiusSm),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state == NodeState.DONE) "✓" else "${i + 1}",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = when (state) { NodeState.DONE -> DashboardTokens.success; NodeState.CURRENT -> MaterialTheme.colorScheme.primary; NodeState.LOCKED -> DashboardTokens.inkMuted },
                        )
                    }
                    Spacer(Modifier.width(Dimens.s12))
                    Column(Modifier.weight(1f)) {
                        Text(stop.title, style = MaterialTheme.typography.titleMedium, color = DashboardTokens.inkStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(labels.kind(stop), style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
                    }
                    val earned = stars.getOrNull(i)
                    when {
                        earned != null -> StarRow(3, earned, starSize = 16.dp)
                        state == NodeState.DONE -> DashboardPill(labels.completed, variant = DashboardPillVariant.SUCCESS)
                        state == NodeState.CURRENT -> DashboardPill(labels.current, variant = DashboardPillVariant.INFO)
                    }
                }
            }
        }
    }
}

/** The lesson's levels as a segmented control; a level the student has not reached is shown locked. */
@Composable
fun LevelSelector(unlocked: List<Int>, completed: List<Int>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, levels: List<Int> = listOf(1, 2, 3)) {
    val labels = LocalJourneyLabels.current
    Row(modifier.fillMaxWidth().padding(horizontal = Dimens.s16), horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
        // [levels] is normally all three; a school with `levels.three` off is shown two, and the third level is
        // simply not part of the lesson rather than a locked door the student keeps tapping.
        levels.forEach { lvl ->
            val open = lvl in unlocked
            val selected = lvl == current
            val shape = RoundedCornerShape(DashboardTokens.radiusSm)
            val levelWord = labels.level.replace("{n}", "$lvl")
            Column(
                Modifier.weight(1f).heightIn(min = Dimens.minTarget)
                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else if (open) MaterialTheme.colorScheme.surface else DashboardTokens.bgSubtle, shape)
                    .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else DashboardTokens.ruleControl, shape)
                    .clip(shape)
                    .clickable(enabled = open, role = Role.Button) { onSelect(lvl) }.padding(6.dp)
                    .semantics { contentDescription = "$levelWord ${labels.levelNames[lvl].orEmpty()}" + if (!open) ", ${labels.locked}" else if (lvl in completed) ", ${labels.completed}" else "" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    levelWord + if (lvl in completed) " ✓" else "",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
                    color = if (selected) MaterialTheme.colorScheme.primary else if (open) DashboardTokens.ink else DashboardTokens.inkMuted,
                )
                Text(
                    if (open) labels.levelNames[lvl].orEmpty() else labels.locked,
                    style = MaterialTheme.typography.bodySmall, color = if (open) DashboardTokens.inkSoft else DashboardTokens.inkMuted, textAlign = TextAlign.Center, maxLines = 1,
                )
            }
        }
    }
}

/** The certificate of a finished lesson: the student, the lesson, the level and the stars — never a percentage (§7). */
@Composable
fun Certificate(childName: String, lessonTitle: String, level: Int, stars: Int, starsTotal: Int, dateText: String, modifier: Modifier = Modifier) {
    val labels = LocalJourneyLabels.current
    val levelWord = labels.level.replace("{n}", "$level")
    DashboardCard(
        modifier.semantics { contentDescription = "${labels.certificate}: $childName, $lessonTitle, $levelWord" },
        padding = androidx.compose.foundation.layout.PaddingValues(Dimens.s24),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(labels.certificate, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Dimens.s12))
            Text(labels.certificateFor, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
            Text(childName.ifBlank { labels.studentFallback }, style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Dimens.s8))
            Text(labels.certificateLesson, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
            Text(lessonTitle, style = MaterialTheme.typography.titleLarge, color = DashboardTokens.inkStrong, textAlign = TextAlign.Center)
            Text(levelWord, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
            Spacer(Modifier.height(Dimens.s12))
            StarRow(total = 3, filled = ((stars * 3f) / starsTotal.coerceAtLeast(1)).let { kotlin.math.round(it).toInt() }.coerceIn(1, 3), starSize = 26.dp)
            Spacer(Modifier.height(Dimens.s8))
            Text(dateText, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkMuted)
        }
    }
}
