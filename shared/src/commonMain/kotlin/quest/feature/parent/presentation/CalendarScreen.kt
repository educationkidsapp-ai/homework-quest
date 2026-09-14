package quest.feature.parent.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import quest.api.dto.Subject
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.domain.CalendarUseCase
import quest.ui.design.Dimens
import quest.ui.design.Palette

object CalendarContract {
    data class State(val year: Int = 2026, val month: Int = 1, val today: LocalDate = LocalDate(2026, 1, 1), val selected: LocalDate? = null, val days: Map<LocalDate, CalendarDay> = emptyMap()) : MviState
    sealed interface Intent : MviIntent { data object Load : Intent; data class Select(val date: LocalDate) : Intent; data class ShiftMonth(val delta: Int) : Intent }
    sealed interface Effect : MviEffect
}

class CalendarViewModel(private val calendar: CalendarUseCase, private val children: ChildrenRepository) : MviViewModel<CalendarContract.State, CalendarContract.Intent, CalendarContract.Effect>(CalendarContract.State()) {
    override suspend fun handle(intent: CalendarContract.Intent) {
        when (intent) {
            CalendarContract.Intent.Load -> { val today = Today.date(); reduce { copy(year = today.year, month = today.monthNumber, today = today, selected = today) }; loadMonth() }
            is CalendarContract.Intent.Select -> reduce { copy(selected = intent.date) }
            is CalendarContract.Intent.ShiftMonth -> { val first = LocalDate(current.year, current.month, 1).plus(intent.delta, DateTimeUnit.MONTH); reduce { copy(year = first.year, month = first.monthNumber) }; loadMonth() }
        }
    }
    private suspend fun loadMonth() {
        val child = children.currentChild.value ?: return
        val days = runCatching { calendar(child, current.year, current.month, current.today) }.getOrDefault(emptyList())
        reduce { copy(days = days.associateBy { it.date }) }
    }
}

@Composable
fun CalendarRoute(onLessonPanel: (String) -> Unit, onBack: () -> Unit) {
    val vm: CalendarViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(CalendarContract.Intent.Load) }
    ParentShell(title = { it.calendar }, onBack = onBack) { s -> CalendarScreen(state, s, vm::dispatch, onLessonPanel) }
}

/** Screen 20: month grid with dots for published lessons; the selected day lists what the admin published and how the child did. */
@Composable
fun CalendarScreen(state: CalendarContract.State, s: Strings, dispatch: (CalendarContract.Intent) -> Unit, onLessonPanel: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ dispatch(CalendarContract.Intent.ShiftMonth(-1)) }, Modifier.semantics { contentDescription = "Previous month" }) { Text("‹", style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk) }
            Text("${s.months[state.month - 1]} ${state.year}", style = MaterialTheme.typography.titleLarge, color = Palette.parentInk, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            IconButton({ dispatch(CalendarContract.Intent.ShiftMonth(1)) }, Modifier.semantics { contentDescription = "Next month" }) { Text("›", style = MaterialTheme.typography.headlineMedium, color = Palette.parentInk) }
        }
        Row(Modifier.fillMaxWidth()) { s.weekdays.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Palette.parentInkSoft, textAlign = TextAlign.Center) } }
        val first = LocalDate(state.year, state.month, 1)
        val offset = first.dayOfWeek.isoDayNumber - 1
        val daysInMonth = first.plus(1, DateTimeUnit.MONTH).toEpochDays() - first.toEpochDays()
        val cells = List(offset) { null } + (1..daysInMonth).map { LocalDate(state.year, state.month, it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val day = state.days[date]; val selected = date == state.selected
                            Column(
                                Modifier.size(44.dp).background(if (selected) Palette.parentAccent else Color.Transparent).clickable(role = Role.Button) { dispatch(CalendarContract.Intent.Select(date)) }
                                    .semantics { contentDescription = "${date.dayOfMonth} ${s.months[date.monthNumber - 1]}" + (day?.let { ", ${it.subjects.size} lessons" } ?: "") },
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                            ) {
                                Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyLarge, color = if (selected) Color.White else if (date == state.today) Palette.parentAccent else Palette.parentInk)
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { day?.subjects?.forEach { sub -> Box(Modifier.size(6.dp).background(if (sub == Subject.MATH) Palette.sunDeep else Palette.lavender)) } }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(Dimens.s16))
        state.selected?.let { date ->
            SectionTitle(if (date == state.today) s.today else "${date.dayOfMonth} ${s.months[date.monthNumber - 1]}")
            val day = state.days[date]
            if (day == null) ParentCard { Text(s.noLessonsThatDay, style = MaterialTheme.typography.bodyLarge, color = Palette.parentInkSoft) }
            else day.lessonIds.forEachIndexed { i, id ->
                ParentCard(Modifier.padding(bottom = Dimens.s8), onClick = { onLessonPanel(id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (day.subjects.getOrNull(i) == Subject.MATH) "🔢 ${s.math}" else "📖 ${s.english}", style = MaterialTheme.typography.titleMedium, color = Palette.parentInk, modifier = Modifier.weight(1f))
                        Chip(if (id in day.doneIds) s.played else s.notPlayed, if (id in day.doneIds) Palette.mint else Palette.parentAccentSoft)
                    }
                }
            }
        }
        Spacer(Modifier.height(Dimens.s24))
    }
}
