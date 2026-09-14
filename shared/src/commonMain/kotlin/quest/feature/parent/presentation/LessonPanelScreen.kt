package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import quest.core.platform.MediaFiles
import quest.core.platform.rememberStopMedia
import quest.feature.children.domain.ChildrenRepository
import quest.feature.content.domain.JourneyRepository
import quest.feature.content.domain.StopMediaRecord
import quest.ui.stops.DrawingPreview
import quest.api.dto.PublishedLesson
import quest.feature.content.domain.LessonRepository
import quest.ui.design.Dimens
import quest.ui.design.Palette

@Composable
fun LessonPanelRoute(lessonId: String, onBack: () -> Unit) {
    val lessons: LessonRepository = koinInject()
    val journey: JourneyRepository = koinInject()
    val children: ChildrenRepository = koinInject()
    var lesson by remember { mutableStateOf<PublishedLesson?>(null) }
    var media by remember { mutableStateOf<List<StopMediaRecord>>(emptyList()) }
    LaunchedEffect(lessonId) {
        lesson = runCatching { lessons.lesson(lessonId) }.getOrNull()
        children.currentChild.value?.let { media = journey.media(it.id, lessonId) }
    }
    val player = rememberStopMedia()
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf<List<Int>>(listOf(1)) }
    LaunchedEffect(lessonId) {
        children.currentChild.value?.let { c -> unlocked = quest.api.map.MapAssembler.unlockedLevels(journey.completions(c.id).filter { it.lessonId == lessonId }, journey.parentUnlocks(c.id)[lessonId].orEmpty()) }
    }
    ParentShell(title = { it.lessonPanel }, onBack = onBack) { s ->
        lesson?.let {
            LessonPanelScreen(it, s, media, unlocked = unlocked,
                onPlay = { path -> MediaFiles.read(path)?.let { b -> scope.launch { player.play(b) } } },
                onUnlock = { level -> scope.launch { children.currentChild.value?.let { c -> journey.unlockLevel(c.id, lessonId, level); unlocked = (unlocked + level).distinct().sorted() } } })
        }
    }
}

/** The 👩‍🏫 panel: bilingual objectives, Supported and Challenge ideas, one tip per stop, and the child's saved retells / drawings beside the model answer. */
@Composable
fun LessonPanelScreen(lesson: PublishedLesson, s: Strings, media: List<StopMediaRecord> = emptyList(), unlocked: List<Int> = listOf(1), onPlay: (String) -> Unit = {}, onUnlock: (Int) -> Unit = {}) {
    val ar = s.isRtl
    val panel = lesson.parentPanel
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        Text(lesson.title, style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk, modifier = Modifier.padding(vertical = Dimens.s8))
        SectionTitle(s.objectives)
        ParentCard { (if (ar) panel.objectives.ar else panel.objectives.en).forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk) } }
        SectionTitle(s.supported)
        ParentCard { panel.supported.forEach { Text("• ${if (ar) it.ar else it.en}", style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk) } }
        SectionTitle(s.challengeIdeas)
        ParentCard { panel.challenge.forEach { Text("• ${if (ar) it.ar else it.en}", style = MaterialTheme.typography.bodyLarge, color = Palette.parentInk) } }
        SectionTitle(s.tipsPerStop)
        lesson.plays.forEach { play ->
            androidx.compose.foundation.layout.Row(Modifier.padding(top = Dimens.s8), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${s.level} ${play.level}", style = MaterialTheme.typography.titleMedium, color = Palette.parentInkSoft, modifier = Modifier.weight(1f))
                if (play.level in unlocked) Chip("✓", Palette.mint) else Chip(s.unlockLevel, Palette.parentAccentSoft) { onUnlock(play.level) }
            }
            play.stops.forEach { stop ->
                ParentCard(Modifier.padding(top = Dimens.s8)) {
                    Text(stop.title, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk)
                    Text(if (ar) stop.parentTip.ar else stop.parentTip.en, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                    panel.modelAnswers.firstOrNull { it.stopId == stop.id }?.let { Spacer(Modifier.height(Dimens.s4)); Text("✔ ${it.en}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk) }
                    media.filter { it.stopId == stop.id }.forEach { m ->
                        Spacer(Modifier.height(Dimens.s8))
                        m.recordingPath?.let { path -> ParentButton("▶ ${s.playRecording}", { onPlay(path) }, primary = false) }
                        m.drawingPath?.let { path -> MediaFiles.read(path)?.decodeToString()?.let { DrawingPreview(it, Modifier.padding(top = Dimens.s8)) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}
