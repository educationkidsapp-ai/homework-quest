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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.api.staff.LeaveTypeBalance
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.GateFallback
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * Feature 34's "رصيدي / My balance": the year, her staff category, and one row per leave type her school offers —
 * the yearly allowance, the days approved leave has used and what is left. A type with no allowance (unpaid, other)
 * says so in words rather than showing a zero, which would read as "none left".
 */
@Composable
fun StaffMyBalanceRoute(onBack: () -> Unit) {
    val vm: MyLeaveBalanceViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(MyBalanceContract.Intent.Load) }
    StaffShell(title = { it.myBalance }, currentTab = null, onTabSelected = {}, onBack = onBack) { s, _ ->
        GateFallback(Flags.STAFF_LEAVE, onClosed = onBack)
        StaffMyBalanceScreen(state, s)
    }
}

@Composable
fun StaffMyBalanceScreen(state: MyBalanceContract.State, strings: StaffStrings) {
    // Both switches, as the server's routes ask: a school without check-in has no attendance for a leave to excuse.
    if (featureEnabled(Flags.STAFF_ATTENDANCE) && featureEnabled(Flags.STAFF_LEAVE)) {
        val balance = state.balance
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16, vertical = Dimens.s8)) {
            when {
                state.loading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
                balance == null -> ParentCard(Modifier.fillMaxWidth()) {
                    Text(
                        if (state.failed) strings.recordFailed else strings.noBalance,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.inkSoft,
                    )
                }
                else -> {
                    Text(
                        strings.balanceYear
                            .replace("{year}", balance.year.toString())
                            .replace("{category}", strings.categoryName(balance.category)),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = DashboardTokens.inkStrong,
                    )
                    Spacer(Modifier.height(Dimens.s8))
                    val offered = balance.types.filter { it.enabled }
                    if (offered.isEmpty()) {
                        ParentCard(Modifier.fillMaxWidth()) {
                            Text(strings.noBalance, style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.inkSoft)
                        }
                    } else {
                        offered.forEach { row -> BalanceRow(row, strings) }
                    }
                    Spacer(Modifier.height(Dimens.s8))
                    Text(
                        strings.chainName(balance.chain),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                }
            }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
}

@Composable
private fun BalanceRow(row: LeaveTypeBalance, strings: StaffStrings) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    strings.leaveTypeName(row.type),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.ink,
                )
                Spacer(Modifier.height(Dimens.s4))
                Text(
                    strings.balanceLine(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
                row.documentAfterDays?.let { days ->
                    Text(
                        strings.documentAfterDays.replace("{n}", days.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                }
            }
            // A type with no allowance says so once, in the line above; a chip repeating it would read as a number.
            row.remainingDays?.let { left ->
                Chip(strings.remainingDays.replace("{n}", left.toString()), DashboardTokens.secondarySoft)
            }
        }
    }
}
