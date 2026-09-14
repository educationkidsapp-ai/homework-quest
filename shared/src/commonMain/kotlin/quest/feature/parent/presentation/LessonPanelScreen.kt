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
import quest.api.dto.PublishedLesson
import quest.feature.content.domain.LessonRepository
import quest.ui.design.Dimens
import quest.ui.design.Palette

@Composable
fun LessonPanelRoute(lessonId: String, onBack: () -> Unit) {
    val lessons: LessonRepository = koinInject()
    var lesson by remember { mutableStateOf<PublishedLesson?>(null) }
    LaunchedEffect(lessonId) { lesson = runCatching { lessons.lesson(lessonId) }.getOrNull() }
    ParentShell(title = { it.lessonPanel }, onBack = onBack) { s -> lesson?.let { LessonPanelScreen(it, s) } }
}

/** The 👩‍🏫 panel: bilingual objectives, Supported and Challenge ideas, one tip per stop (and model answers). */
@Composable
fun LessonPanelScreen(lesson: PublishedLesson, s: Strings) {
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
            Text("${s.level} ${play.level}", style = MaterialTheme.typography.titleMedium, color = Palette.parentInkSoft, modifier = Modifier.padding(top = Dimens.s8))
            play.stops.forEach { stop ->
                ParentCard(Modifier.padding(top = Dimens.s8)) {
                    Text(stop.title, style = MaterialTheme.typography.titleMedium, color = Palette.parentInk)
                    Text(if (ar) stop.parentTip.ar else stop.parentTip.en, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                    panel.modelAnswers.firstOrNull { it.stopId == stop.id }?.let { Spacer(Modifier.height(Dimens.s4)); Text("✔ ${it.en}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInk) }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}
