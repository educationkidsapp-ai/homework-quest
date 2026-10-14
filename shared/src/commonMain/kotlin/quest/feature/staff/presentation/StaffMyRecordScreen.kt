package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.staff.CheckInDay
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate
import quest.feature.school.presentation.GateFallback
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 15's "سجلي / My record": the month the server answered with, one line per day — the date, the check-in, the
 * check-out, the status and the mode. Nothing is computed here; a phone with a wrong clock still reads the right rows.
 */
@Composable
fun StaffMyRecordRoute(onBack: () -> Unit) {
    val vm: MyRecordViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(MyRecordContract.Intent.Load) }
    StaffShell(title = { it.myRecord }, currentTab = null, onTabSelected = {}, onBack = onBack) { s, _ ->
        GateFallback(Flags.STAFF_ATTENDANCE, onClosed = onBack)
        StaffMyRecordScreen(
            state = state,
            strings = s,
            onPreviousMonth = { vm.dispatch(MyRecordContract.Intent.PreviousMonth) },
            onNextMonth = { vm.dispatch(MyRecordContract.Intent.NextMonth) },
        )
    }
}

@Composable
fun StaffMyRecordScreen(
    state: MyRecordContract.State,
    strings: StaffStrings,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    FeatureGate(Flags.STAFF_ATTENDANCE) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16, vertical = Dimens.s8)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                TextButton(onClick = onPreviousMonth) { Text(strings.previousMonth, color = DashboardTokens.accentInk) }
                Text(
                    state.month.orEmpty(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.inkStrong,
                )
                TextButton(onClick = onNextMonth) { Text(strings.nextMonth, color = DashboardTokens.accentInk) }
            }
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
                return@FeatureGate
            }
            if (state.days.isEmpty()) {
                ParentCard(Modifier.padding(vertical = Dimens.s8)) {
                    Text(
                        if (state.failed) strings.recordFailed else strings.noRecordThisMonth,
                        style = MaterialTheme.typography.bodyLarge,
                        color = DashboardTokens.inkSoft,
                    )
                }
                return@FeatureGate
            }
            state.days.forEach { day -> DayRow(day, strings) }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}

@Composable
private fun DayRow(day: CheckInDay, strings: StaffStrings) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    day.date,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.ink,
                )
                Spacer(Modifier.height(Dimens.s4))
                Text(
                    buildString {
                        append(strings.checkedInLabel).append(' ').append(day.checkIn ?: "—")
                        append(" · ").append(strings.checkedOutLabel).append(' ').append(day.checkOut ?: "—")
                        day.mode?.let { append(" · ").append(strings.modeName(it)) }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = DashboardTokens.inkSoft,
                )
            }
            Chip(strings.statusName(day.status), DashboardTokens.secondarySoft)
        }
    }
}
