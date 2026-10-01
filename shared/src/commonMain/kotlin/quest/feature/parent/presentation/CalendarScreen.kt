package quest.feature.parent.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import org.koin.compose.viewmodel.koinViewModel
import quest.api.ContentApi
import quest.api.dto.ChildAttendanceRecord
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.parent.domain.CalendarDay
import quest.feature.parent.domain.CalendarUseCase
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTokens
import quest.ui.design.SubjectMeta

object CalendarContract {
    data class State(
        val year: Int = 2026,
        val month: Int = 1,
        val today: LocalDate = LocalDate(2026, 1, 1),
        val selected: LocalDate? = null,
        val days: Map<LocalDate, CalendarDay> = emptyMap(),
        val attendance: Map<LocalDate, ChildAttendanceRecord> = emptyMap(),
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Select(val date: LocalDate) : Intent
        data class ShiftMonth(val delta: Int) : Intent
    }
    sealed interface Effect : MviEffect
}

class CalendarViewModel(
    private val calendar: CalendarUseCase,
    private val children: ChildrenRepository,
    private val api: ContentApi,
) : MviViewModel<CalendarContract.State, CalendarContract.Intent, CalendarContract.Effect>(CalendarContract.State()) {
    override suspend fun handle(intent: CalendarContract.Intent) {
        when (intent) {
            CalendarContract.Intent.Load -> {
                val today = Today.date()
                reduce { copy(year = today.year, month = today.monthNumber, today = today, selected = today) }
                loadMonth()
            }
            is CalendarContract.Intent.Select -> reduce { copy(selected = intent.date) }
            is CalendarContract.Intent.ShiftMonth -> {
                val first = LocalDate(current.year, current.month, 1).plus(intent.delta, DateTimeUnit.MONTH)
                reduce { copy(year = first.year, month = first.monthNumber) }
                loadMonth()
            }
        }
    }
    private suspend fun loadMonth() {
        val child = children.currentChild.value ?: return
        val days = runCatching { calendar(child, current.year, current.month, current.today) }.getOrDefault(emptyList())
        val first = LocalDate(current.year, current.month, 1)
        val last = first.plus(1, DateTimeUnit.MONTH)
        val attendanceResp = runCatching { api.childAttendance(child.id, from = first.toString(), to = last.toString()) }.getOrNull()
        val attendanceMap = attendanceResp?.records?.mapNotNull { r ->
            runCatching { LocalDate.parse(r.date) }.getOrNull()?.let { it to r }
        }?.toMap() ?: emptyMap()
        reduce { copy(days = days.associateBy { it.date }, attendance = attendanceMap) }
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
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // Month Navigation Bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { dispatch(CalendarContract.Intent.ShiftMonth(-1)) },
                modifier = Modifier.semantics { contentDescription = "Previous month" },
            ) {
                Text("‹", style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.ink)
            }
            Text(
                text = "${s.months[state.month - 1]} ${state.year}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = DashboardTokens.inkStrong,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            IconButton(
                onClick = { dispatch(CalendarContract.Intent.ShiftMonth(1)) },
                modifier = Modifier.semantics { contentDescription = "Next month" },
            ) {
                Text("›", style = MaterialTheme.typography.headlineMedium, color = DashboardTokens.ink)
            }
        }

        // Weekday Headers
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            s.weekdays.forEach {
                Text(
                    text = it,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = DashboardTokens.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Days Grid
        val first = LocalDate(state.year, state.month, 1)
        val offset = first.dayOfWeek.isoDayNumber - 1
        val daysInMonth = first.plus(1, DateTimeUnit.MONTH).toEpochDays() - first.toEpochDays()
        val cells = List(offset) { null } + (1..daysInMonth).map { LocalDate(state.year, state.month, it) }

        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(Modifier.weight(1f).height(50.dp), contentAlignment = Alignment.Center) {
                        if (date != null) {
                            val day = state.days[date]
                            val selected = date == state.selected
                            val isToday = date == state.today
                            Column(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            selected -> MaterialTheme.colorScheme.primary
                                            isToday -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        },
                                        CircleShape,
                                    )
                                    .then(
                                        if (isToday && !selected) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier,
                                    )
                                    .clickable(role = Role.Button) { dispatch(CalendarContract.Intent.Select(date)) }
                                    .semantics {
                                        contentDescription = "${date.dayOfMonth} ${s.months[date.monthNumber - 1]}" +
                                            (day?.let { ", ${it.subjects.size} lessons" } ?: "")
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = date.dayOfMonth.toString(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    ),
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.onPrimary
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> DashboardTokens.ink
                                    },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    day?.subjects?.forEach { sub ->
                                        Box(
                                            Modifier
                                                .size(5.dp)
                                                .clip(CircleShape)
                                                .background(SubjectMeta.of(sub).color),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Selected Day Details
        state.selected?.let { date ->
            SectionTitle(if (date == state.today) s.today else "${date.dayOfMonth} ${s.months[date.monthNumber - 1]}")
            val att = state.attendance[date]
            if (att != null) {
                ParentCard(Modifier.padding(bottom = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            val statusText = when (att.status.uppercase()) {
                                "PRESENT" -> "✓ ${s.present}"
                                "LATE" -> "⏰ ${s.late}"
                                "ABSENT" -> "✕ ${s.absent}"
                                "EXCUSED" -> "ℹ ${s.excused}"
                                else -> att.status
                            }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = DashboardTokens.inkStrong,
                            )
                            if (!att.notes.isNullOrBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "${s.attendanceNote}: ${att.notes}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DashboardTokens.inkSoft,
                                )
                            }
                        }
                        val variant = when (att.status.uppercase()) {
                            "PRESENT" -> DashboardPillVariant.SUCCESS
                            "LATE" -> DashboardPillVariant.WARNING
                            "ABSENT" -> DashboardPillVariant.ERROR
                            "EXCUSED" -> DashboardPillVariant.INFO
                            else -> DashboardPillVariant.NEUTRAL
                        }
                        val chipText = when (att.status.uppercase()) {
                            "PRESENT" -> s.present
                            "LATE" -> s.late
                            "ABSENT" -> s.absent
                            "EXCUSED" -> s.excused
                            else -> att.status
                        }
                        DashboardPill(text = chipText, variant = variant)
                    }
                }
            }

            val day = state.days[date]
            if (day == null && att == null) {
                ParentCard {
                    Text(s.noLessonsThatDay, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                }
            } else {
                day?.lessonIds?.forEachIndexed { i, id ->
                    val meta = SubjectMeta.of(day.subjects.getOrNull(i))
                    val isDone = id in day.doneIds
                    ParentCard(
                        modifier = Modifier.padding(bottom = 8.dp),
                        onClick = { onLessonPanel(id) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(meta.emoji, fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = meta.label(s.isRtl),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = DashboardTokens.inkStrong,
                                )
                            }
                            DashboardPill(
                                text = if (isDone) s.played else s.notPlayed,
                                variant = if (isDone) DashboardPillVariant.SUCCESS else DashboardPillVariant.NEUTRAL,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
