package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.ChildAttendanceRecord
import quest.api.dto.Curriculum
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.Dimens
import quest.ui.design.Palette
import quest.ui.design.Pip
import quest.ui.design.PipPose

object ParentHomeContract {
    data class State(
        val loading: Boolean = true, val children: List<Child> = emptyList(), val current: Child? = null, val today: List<CalendarDay> = emptyList(),
        /** Child id → the section the child was placed in, when the parent added her with a class join code (§2). */
        val sections: Map<String, String> = emptyMap(),
        val attendance: ChildAttendanceRecord? = null,
    ) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data class Select(val id: String) : Intent; data object SignOut : Intent }
    sealed interface Effect : MviEffect { data object NeedsChild : Effect; data object SignedOut : Effect }
}

class ParentHomeViewModel(
    private val children: ChildrenRepository,
    private val calendar: CalendarUseCase,
    private val auth: AuthProvider,
    private val api: ContentApi,
) : MviViewModel<ParentHomeContract.State, ParentHomeContract.Intent, ParentHomeContract.Effect>(ParentHomeContract.State()) {
    override suspend fun handle(intent: ParentHomeContract.Intent) {
        when (intent) {
            ParentHomeContract.Intent.Load -> {
                val list = children.refresh()
                val sections = list.mapNotNull { c -> children.sectionName(c.id)?.let { c.id to it } }.toMap()
                val current = children.currentChild.value
                if (current == null) { reduce { copy(loading = false, children = list, sections = sections) }; effect(ParentHomeContract.Effect.NeedsChild); return }
                val today = Today.date()
                val days = runCatching { calendar(current, today.year, today.monthNumber, today) }.getOrDefault(emptyList()).filter { it.date == today }
                val att = runCatching { api.todayAttendance(current.id) }.getOrNull()
                reduce { copy(loading = false, children = list, current = current, today = days, sections = sections, attendance = att) }
            }
            is ParentHomeContract.Intent.Select -> { children.select(intent.id); handle(ParentHomeContract.Intent.Load) }
            ParentHomeContract.Intent.SignOut -> { auth.signOut(); children.clear(); effect(ParentHomeContract.Effect.SignedOut) }
        }
    }
}

@Composable
fun ParentHomeRoute(
    onAddChild: () -> Unit, onEditChild: (String) -> Unit, onCalendar: () -> Unit, onProgress: () -> Unit,
    onSettings: () -> Unit, onLessonPanel: (String) -> Unit, onSignedOut: () -> Unit, onExit: () -> Unit,
    onMessages: () -> Unit = {},
) {
    val vm: ParentHomeViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(ParentHomeContract.Intent.Load)
        vm.effects.collect { when (it) { ParentHomeContract.Effect.NeedsChild -> onAddChild(); ParentHomeContract.Effect.SignedOut -> onSignedOut() } }
    }
    ParentShell(title = { it.parentHome }, onBack = onExit) { s -> ParentHomeScreen(state, s, vm::dispatch, onAddChild, onEditChild, onCalendar, onProgress, onSettings, onLessonPanel, onMessages) }
}

@Composable
fun ParentHomeScreen(
    state: ParentHomeContract.State, s: Strings, dispatch: (ParentHomeContract.Intent) -> Unit, onAddChild: () -> Unit, onEditChild: (String) -> Unit,
    onCalendar: () -> Unit, onProgress: () -> Unit, onSettings: () -> Unit, onLessonPanel: (String) -> Unit,
    onMessages: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        SectionTitle(s.children)
        state.children.forEach { c ->
            val selected = c.id == state.current?.id
            ParentCard(Modifier.padding(bottom = Dimens.s8), onClick = { if (selected) onEditChild(c.id) else dispatch(ParentHomeContract.Intent.Select(c.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pip(PipPose.IDLE, 52.dp, animated = false, color = c.avatarColor)
                    Spacer(Modifier.size(Dimens.s12))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.titleLarge, color = Palette.parentInk)
                        // The section when the child has one, the course otherwise — "1A British" says more to a
                        // parent than "British · Grade 1", and it is the line the teacher will name on the phone.
                        Text(state.sections[c.id] ?: "${if (c.curriculum == Curriculum.BRITISH) s.british else s.american} · ${s.grade} ${c.grade}", style = MaterialTheme.typography.bodyMedium, color = Palette.parentInkSoft)
                    }
                    if (selected) Chip("✓")
                }
            }
        }
        ParentButton(s.addChild, onAddChild, primary = false, icon = "＋")

        SectionTitle(s.todaysAttendance)
        ParentCard(Modifier.padding(bottom = Dimens.s8)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val statusText = when (state.attendance?.status?.uppercase()) {
                        "PRESENT" -> "✓ ${s.present}"
                        "LATE" -> "⏰ ${s.late}"
                        "ABSENT" -> "✕ ${s.absent}"
                        "EXCUSED" -> "ℹ ${s.excused}"
                        else -> s.noAttendanceRecorded
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.attendance != null) Palette.parentInk else Palette.parentInkSoft,
                    )
                    if (!state.attendance?.notes.isNullOrBlank()) {
                        Spacer(Modifier.height(Dimens.s4))
                        Text(
                            "${s.attendanceNote}: ${state.attendance!!.notes}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.parentInkSoft,
                        )
                    }
                }
                if (state.attendance != null) {
                    val chipColor = when (state.attendance.status.uppercase()) {
                        "PRESENT" -> Palette.mint
                        "LATE" -> Palette.sunDeep
                        "ABSENT" -> Palette.coral
                        "EXCUSED" -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val chipText = when (state.attendance.status.uppercase()) {
                        "PRESENT" -> s.present
                        "LATE" -> s.late
                        "ABSENT" -> s.absent
                        "EXCUSED" -> s.excused
                        else -> state.attendance.status
                    }
                    Chip(chipText, chipColor)
                }
            }
        }

        SectionTitle(s.todaysLessons)
        if (state.today.isEmpty() && !state.loading) ParentCard { Text(s.noLessonsToday, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft) }
        state.today.forEach { day ->
            day.lessonIds.forEachIndexed { i, id ->
                ParentCard(Modifier.padding(bottom = Dimens.s8), onClick = { onLessonPanel(id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (day.subjects.getOrNull(i) == quest.api.dto.Subject.MATH) "🔢 ${s.math}" else "📖 ${s.english}", style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
                        Chip(if (id in day.doneIds) s.played else s.notPlayed, if (id in day.doneIds) Palette.mint else MaterialTheme.colorScheme.primaryContainer)
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s12)) {
            ParentButton(s.calendar, onCalendar, Modifier.weight(1f), primary = false, icon = "📅")
            ParentButton(s.progress, onProgress, Modifier.weight(1f), primary = false, icon = "📈")
        }
        Spacer(Modifier.height(Dimens.s12))
        FeatureGate(Flags.CHAT) {
            ParentButton(s.messages, onMessages, primary = false, icon = "💬")
            Spacer(Modifier.height(Dimens.s12))
        }
        ParentButton(s.settings, onSettings, primary = false, icon = "⚙️")
        Spacer(Modifier.height(Dimens.s12))
        ParentButton(s.signOut, { dispatch(ParentHomeContract.Intent.SignOut) }, primary = false)
        Spacer(Modifier.height(Dimens.s24))
    }
}
