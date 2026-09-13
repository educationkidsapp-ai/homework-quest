package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.LessonStatus
import quest.api.dto.Subject
import quest.core.design.Dimens
import quest.core.design.Palette
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.lesson.data.wire
import quest.feature.lesson.domain.Lesson
import quest.feature.lesson.domain.LessonRepository
import quest.feature.lesson.domain.Skill
import quest.feature.parent.domain.ChildProfile
import quest.feature.parent.domain.ParentRepository
import quest.feature.parent.domain.RequeueWeakSkillsUseCase

object ParentHomeContract {
    data class LessonCard(val lesson: Lesson, val skills: List<Skill>)
    data class State(val loading: Boolean = true, val profile: ChildProfile? = null, val lessons: List<LessonCard> = emptyList(), val requeuedCount: Int = 0) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent }
    sealed interface Effect : MviEffect { data object NeedsProfile : Effect }
}

class ParentHomeViewModel(private val parent: ParentRepository, private val lessons: LessonRepository, private val requeue: RequeueWeakSkillsUseCase) :
    MviViewModel<ParentHomeContract.State, ParentHomeContract.Intent, ParentHomeContract.Effect>(ParentHomeContract.State()) {
    override suspend fun handle(intent: ParentHomeContract.Intent) {
        val today = Today.date()
        val requeued = runCatching { requeue(today) }.getOrDefault(emptyList())
        val profile = parent.profile()
        val cards = lessons.lessonsOn(today).map { ParentHomeContract.LessonCard(it, lessons.skillsForLesson(it.id)) }
        reduce { copy(loading = false, profile = profile, lessons = cards, requeuedCount = requeued.size) }
        if (profile.name.isBlank()) effect(ParentHomeContract.Effect.NeedsProfile)
    }
}

@Composable
fun ParentHomeRoute(onAddLesson: () -> Unit, onOpenLesson: (Lesson) -> Unit, onCalendar: () -> Unit, onProgress: () -> Unit, onSettings: () -> Unit, onProfile: () -> Unit, onExit: () -> Unit) {
    val vm: ParentHomeViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(ParentHomeContract.Intent.Load)
        vm.effects.collect { if (it is ParentHomeContract.Effect.NeedsProfile) onProfile() }
    }
    ParentShell(title = { it.parentHome }, onBack = onExit) { s ->
        ParentHomeScreen(state, s, onAddLesson, onOpenLesson, onCalendar, onProgress, onSettings, onProfile)
    }
}

@Composable
fun ParentHomeScreen(
    state: ParentHomeContract.State, s: Strings, onAddLesson: () -> Unit, onOpenLesson: (Lesson) -> Unit,
    onCalendar: () -> Unit, onProgress: () -> Unit, onSettings: () -> Unit, onProfile: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        state.profile?.let { p ->
            ParentCard(onClick = onProfile) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🧒", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.padding(Dimens.s8))
                    Column {
                        Text(p.name.ifBlank { "—" }, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
                        Text("${s.grade} ${p.grade} · ${p.curriculum}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                    }
                }
            }
        }
        SectionTitle(s.todaysLessons)
        if (state.lessons.isEmpty() && !state.loading) {
            ParentCard { Text(s.noLessonsToday, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft) }
        }
        state.lessons.forEach { card ->
            ParentCard(Modifier.padding(bottom = Dimens.s12), onClick = { onOpenLesson(card.lesson) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (card.lesson.subject == Subject.MATH) "🔢 ${s.math}" else "🔤 ${s.english}", style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
                    val statusColor = when (card.lesson.status) {
                        LessonStatus.READY -> Palette.mint
                        LessonStatus.ERROR -> Palette.coral
                        LessonStatus.NEEDS_CONFIRMATION -> Palette.sun
                        else -> Palette.parentAccentSoft
                    }
                    Chip(s.statusLabel[card.lesson.status.wire()] ?: card.lesson.status.name, statusColor)
                }
                val confirmed = card.skills.filter { it.confirmed }.ifEmpty { card.skills }
                if (confirmed.isNotEmpty()) {
                    Spacer(Modifier.height(Dimens.s8))
                    Text(confirmed.joinToString(" · ") { it.name }, style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                }
            }
        }
        Spacer(Modifier.height(Dimens.s8))
        ParentButton(s.addLesson, onAddLesson, icon = "＋")
        Spacer(Modifier.height(Dimens.s24))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            ParentButton(s.calendar, onCalendar, Modifier.weight(1f), primary = false, icon = "📅")
            ParentButton(s.progress, onProgress, Modifier.weight(1f), primary = false, icon = "📈")
        }
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(s.settings, onSettings, primary = false, icon = "⚙️")
        Spacer(Modifier.height(Dimens.s24))
    }
}
