package quest.feature.parent.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.AuthProvider
import quest.api.ContentApi
import quest.api.dto.Child
import quest.api.dto.ChildAttendanceRecord
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.children.domain.ChildrenRepository
import quest.feature.children.presentation.ChildRow
import quest.feature.children.presentation.NoChildrenLinked
import quest.feature.parent.domain.CalendarDay
import quest.feature.broadcasts.domain.unreadAnnouncements
import quest.feature.parent.domain.CalendarUseCase
import quest.feature.school.domain.FlagStore
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.ui.design.DashboardPill
import quest.ui.design.DashboardPillVariant
import quest.ui.design.DashboardTab
import quest.ui.design.DashboardTokens
import quest.ui.design.SubjectMeta

object ParentHomeContract {
    data class State(
        val loading: Boolean = true,
        val children: List<Child> = emptyList(),
        val current: Child? = null,
        val today: List<CalendarDay> = emptyList(),
        val attendance: ChildAttendanceRecord? = null,
        /** MH3: unread announcements and events, the badge on the Announcements button. Plans are counted apart. */
        val unreadBroadcasts: Int = 0,
        /** MH3: unread weekly plans in the archive window, the badge on the Weekly plan button. */
        val unreadPlans: Int = 0,
    ) : MviState
    sealed interface Intent : MviIntent {
        data object Load : Intent
        data class Select(val id: String) : Intent
        data object SignOut : Intent
    }
    sealed interface Effect : MviEffect { data object SignedOut : Effect }
}

class ParentHomeViewModel(
    private val children: ChildrenRepository,
    private val calendar: CalendarUseCase,
    private val auth: AuthProvider,
    private val api: ContentApi,
    private val flags: FlagStore,
) : MviViewModel<ParentHomeContract.State, ParentHomeContract.Intent, ParentHomeContract.Effect>(ParentHomeContract.State()) {
    override suspend fun handle(intent: ParentHomeContract.Intent) {
        when (intent) {
            ParentHomeContract.Intent.Load -> {
                val list = children.refresh()
                val current = children.currentChild.value
                // Nobody linked yet (or the school unlinked the last child): the home says so instead of asking the
                // parent to add one — only the admin can.
                if (current == null) {
                    reduce { copy(loading = false, children = list, current = null, today = emptyList(), attendance = null, unreadBroadcasts = 0, unreadPlans = 0) }
                    return
                }
                val today = Today.date()
                val days = runCatching { calendar(current, today.year, today.monthNumber, today) }.getOrDefault(emptyList()).filter { it.date == today }
                val att = runCatching { api.todayAttendance(current.id) }.getOrNull()
                // A parent has no bell (runbook "Broadcasts"), so the counts come with the home load — but only when
                // the school has the flag. `announcements` is off in `DEFAULT_FLAGS`, so asking first and swallowing
                // the 404 would mean every school paid a refused request on every home load and every child switch.
                //
                // MH3: two counts, because there are two pages. The feed's own `unread` counts the weekly plans it still
                // carries, so the announcements badge is made from the rows; the plans badge is the archive's `unread`.
                val enabled = flags.isEnabled(Flags.ANNOUNCEMENTS)
                val feed = if (enabled) runCatching { api.childBroadcasts(current.id) }.getOrNull() else null
                val unread = feed?.let { unreadAnnouncements(it.items, Today.epochMillis()) } ?: 0
                val plans = if (enabled) runCatching { api.childWeeklyPlans(current.id).unread }.getOrDefault(0) else 0
                reduce { copy(loading = false, children = list, current = current, today = days, attendance = att, unreadBroadcasts = unread, unreadPlans = plans) }
            }
            is ParentHomeContract.Intent.Select -> {
                children.select(intent.id)
                handle(ParentHomeContract.Intent.Load)
            }
            ParentHomeContract.Intent.SignOut -> {
                auth.signOut()
                children.clear()
                effect(ParentHomeContract.Effect.SignedOut)
            }
        }
    }
}

@Composable
fun ParentHomeRoute(
    onCalendar: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    onLessonPanel: (String) -> Unit,
    onSignedOut: () -> Unit,
    onExit: () -> Unit,
    onMessages: () -> Unit = {},
    onBroadcasts: () -> Unit = {},
    onWeeklyPlan: () -> Unit = {},
) {
    val vm: ParentHomeViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.dispatch(ParentHomeContract.Intent.Load)
        vm.effects.collect {
            when (it) {
                ParentHomeContract.Effect.SignedOut -> onSignedOut()
            }
        }
    }
    ParentShell(
        title = { it.parentHome },
        onBack = onExit,
        currentTab = DashboardTab.HOME,
        onTabSelected = { tab ->
            when (tab) {
                DashboardTab.HOME -> {}
                DashboardTab.NOTIFICATION -> onBroadcasts()
                DashboardTab.MESSAGES -> onMessages()
                DashboardTab.SETTINGS -> onSettings()
            }
        },
    ) { s ->
        ParentHomeScreen(
            state, s, vm::dispatch, onCalendar,
            onProgress, onSettings, onLessonPanel, onMessages, onBroadcasts, onWeeklyPlan,
        )
    }
}

@Composable
fun ParentHomeScreen(
    state: ParentHomeContract.State,
    s: Strings,
    dispatch: (ParentHomeContract.Intent) -> Unit,
    onCalendar: () -> Unit,
    onProgress: () -> Unit,
    onSettings: () -> Unit,
    onLessonPanel: (String) -> Unit,
    onMessages: () -> Unit = {},
    onBroadcasts: () -> Unit = {},
    onWeeklyPlan: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // ---- Children Section ------------------------------------------------
        SectionTitle(s.children)
        // Every child the school linked to this account; the admin adds them, so there is nothing to add here.
        if (state.children.isEmpty() && !state.loading) NoChildrenLinked(s) { dispatch(ParentHomeContract.Intent.Load) }
        state.children.forEach { c ->
            ChildRow(c, s, selected = c.id == state.current?.id, onClick = { dispatch(ParentHomeContract.Intent.Select(c.id)) })
        }

        // ---- Attendance Section ----------------------------------------------
        SectionTitle(s.todaysAttendance)
        ParentCard(modifier = Modifier.padding(bottom = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    val statusText = when (state.attendance?.status?.uppercase()) {
                        "PRESENT" -> "✓ ${s.present}"
                        "LATE" -> "⏰ ${s.late}"
                        "ABSENT" -> "✕ ${s.absent}"
                        "EXCUSED" -> "ℹ ${s.excused}"
                        else -> s.noAttendanceRecorded
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (state.attendance != null) DashboardTokens.inkStrong else DashboardTokens.inkSoft,
                    )
                    if (!state.attendance?.notes.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${s.attendanceNote}: ${state.attendance!!.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardTokens.inkSoft,
                        )
                    }
                }
                if (state.attendance != null) {
                    val variant = when (state.attendance.status.uppercase()) {
                        "PRESENT" -> DashboardPillVariant.SUCCESS
                        "LATE" -> DashboardPillVariant.WARNING
                        "ABSENT" -> DashboardPillVariant.ERROR
                        "EXCUSED" -> DashboardPillVariant.INFO
                        else -> DashboardPillVariant.NEUTRAL
                    }
                    val chipText = when (state.attendance.status.uppercase()) {
                        "PRESENT" -> s.present
                        "LATE" -> s.late
                        "ABSENT" -> s.absent
                        "EXCUSED" -> s.excused
                        else -> state.attendance.status
                    }
                    DashboardPill(text = chipText, variant = variant)
                }
            }
        }

        // ---- Today's Lessons Section -----------------------------------------
        SectionTitle(s.todaysLessons)
        if (state.today.isEmpty() && !state.loading) {
            ParentCard {
                Text(s.noLessonsToday, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
            }
        }
        state.today.forEach { day ->
            day.lessonIds.forEachIndexed { i, id ->
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
                            Text(meta.emoji, style = MaterialTheme.typography.titleMedium)
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

        // ---- Quick Actions Grid ----------------------------------------------
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ParentButton(s.calendar, onCalendar, Modifier.weight(1f), primary = false, icon = "📅")
            ParentButton(s.progress, onProgress, Modifier.weight(1f), primary = false, icon = "📈")
        }
        Spacer(Modifier.height(10.dp))
        // MH3: RM4's one School news button is two, each with its own unread count. The count rides on the label — a
        // quick-action button has no badge slot, and "Weekly plan · 1" is what a screen reader says anyway.
        FeatureGate(Flags.ANNOUNCEMENTS) {
            ParentButton(badged(s.weeklyPlan, state.unreadPlans), onWeeklyPlan, primary = false, icon = "🗓️")
            Spacer(Modifier.height(10.dp))
            ParentButton(badged(s.announcements, state.unreadBroadcasts), onBroadcasts, primary = false, icon = "📣")
            Spacer(Modifier.height(10.dp))
        }
        FeatureGate(Flags.CHAT) {
            ParentButton(s.messages, onMessages, primary = false, icon = "💬")
            Spacer(Modifier.height(10.dp))
        }
        ParentButton(s.settings, onSettings, primary = false, icon = "⚙️")
        Spacer(Modifier.height(10.dp))
        ParentButton(s.signOut, { dispatch(ParentHomeContract.Intent.SignOut) }, primary = false)
        Spacer(Modifier.height(24.dp))
    }
}

/** "Announcements · 3" while something is unread, the plain word otherwise. */
private fun badged(label: String, unread: Int) = if (unread > 0) "$label · $unread" else label
