package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.LocalStrings
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.ParentCard
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.FeatureGate

/**
 * Feature 15's Today card: today's times, one main button and the way to "My record".
 *
 * **Two taps.** The button is the first; the device's own prompt is the second. Nothing else stands between an
 * employee at the school door and a recorded arrival — the privacy rationale appears once, before the very first fix
 * is ever taken, and never again.
 */
@Composable
fun CheckInCard(onMyRecord: () -> Unit, onMyBalance: () -> Unit = {}, onMyRequests: () -> Unit = {}) {
    FeatureGate(Flags.STAFF_ATTENDANCE) {
        val vm: CheckInViewModel = koinViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        // Feature 18: the remote-day request lives on this card, one tap from her home, with its own view model.
        val requestsVm: AttendanceRequestsViewModel = koinViewModel()
        val requests by requestsVm.state.collectAsStateWithLifecycle()
        LaunchedEffect(vm) { vm.dispatch(CheckInContract.Intent.Load) }
        CheckInCardContent(
            state = state,
            strings = LocalStaffStrings.current,
            onTap = { vm.dispatch(CheckInContract.Intent.Tap) },
            onRationaleAccepted = { vm.dispatch(CheckInContract.Intent.RationaleAccepted) },
            onRationaleDismissed = { vm.dispatch(CheckInContract.Intent.RationaleDismissed) },
            onMyRecord = onMyRecord,
            onMyBalance = onMyBalance,
            onMyRequests = onMyRequests,
            requests = requests,
            onRequests = requestsVm::dispatch,
        )
    }
}

/** The card itself, with no Koin under it, so a screenshot and a preview can draw every state. */
@Composable
fun CheckInCardContent(
    state: CheckInContract.State,
    strings: StaffStrings,
    onTap: () -> Unit,
    onRationaleAccepted: () -> Unit,
    onRationaleDismissed: () -> Unit,
    onMyRecord: () -> Unit,
    /** Feature 34: "رصيدي" and "طلباتي", their own screens behind `staffLeave`. */
    onMyBalance: () -> Unit = {},
    onMyRequests: () -> Unit = {},
    /** Feature 18: the remote-day request's own state, and null-safe defaults so a screenshot draws the card alone. */
    requests: AttendanceRequestsContract.State = AttendanceRequestsContract.State(loading = false),
    onRequests: (AttendanceRequestsContract.Intent) -> Unit = {},
) {
    ParentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (state.checkingOut) strings.checkOut else strings.checkIn,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = quest.ui.design.DashboardTokens.ink,
                )
                Spacer(Modifier.height(quest.ui.design.Dimens.s4))
                Text(
                    when {
                        state.notEnabled -> strings.attendanceNotEnabled
                        state.today?.workDay == false -> strings.dayOffToday
                        !state.checkedIn -> strings.notCheckedInYet
                        else -> times(state, strings)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = quest.ui.design.DashboardTokens.inkSoft,
                )
            }
            Chip(strings.attendance, quest.ui.design.DashboardTokens.secondarySoft)
        }
        if (!state.notEnabled) {
            Spacer(Modifier.height(quest.ui.design.Dimens.s12))
            ParentButton(
                text = if (state.checkingOut) strings.checkOut else strings.checkIn,
                onClick = onTap,
                enabled = !state.busy && !state.loading && !(state.checkedIn && state.checkedOut),
                icon = "📍",
            )
            state.message?.let { message ->
                Spacer(Modifier.height(quest.ui.design.Dimens.s8))
                Text(
                    strings.checkInMessage(message),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = quest.ui.design.DashboardTokens.ink,
                )
            }
            Spacer(Modifier.height(quest.ui.design.Dimens.s8))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = onMyRecord) {
                    Text(strings.myRecord, color = quest.ui.design.DashboardTokens.accentInk)
                }
                TextButton(onClick = { onRequests(AttendanceRequestsContract.Intent.OpenRemoteDay) }) {
                    Text(strings.requestRemoteDay, color = quest.ui.design.DashboardTokens.accentInk)
                }
            }
            // Feature 34: leave, permission, her balance and her requests — each one tap from this card, so every
            // one of them is the two taps the spec asks for from the staff home.
            FeatureGate(Flags.STAFF_LEAVE) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = { onRequests(AttendanceRequestsContract.Intent.OpenLeave) }) {
                        Text(strings.requestLeave, color = quest.ui.design.DashboardTokens.accentInk)
                    }
                    TextButton(onClick = { onRequests(AttendanceRequestsContract.Intent.OpenPermission) }) {
                        Text(strings.requestPermission, color = quest.ui.design.DashboardTokens.accentInk)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    TextButton(onClick = onMyBalance) {
                        Text(strings.myBalance, color = quest.ui.design.DashboardTokens.accentInk)
                    }
                    TextButton(onClick = onMyRequests) {
                        Text(strings.myRequests, color = quest.ui.design.DashboardTokens.accentInk)
                    }
                }
            }
            if (requests.form == null) {
                requests.message?.let { message ->
                    Text(
                        strings.requestMessage(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = quest.ui.design.DashboardTokens.inkSoft,
                    )
                }
            }
        }
    }
    AttendanceRequestDialog(requests, strings, LocalStrings.current, onRequests)
    if (state.rationale) {
        AlertDialog(
            onDismissRequest = onRationaleDismissed,
            title = { Text(strings.privacyTitle, color = quest.ui.design.DashboardTokens.inkStrong) },
            text = { Text(strings.privacyBody, color = quest.ui.design.DashboardTokens.inkSoft) },
            confirmButton = { TextButton(onClick = onRationaleAccepted) { Text(strings.continueWord) } },
            dismissButton = { TextButton(onClick = onRationaleDismissed) { Text(strings.cancel) } },
        )
    }
}

private fun times(state: CheckInContract.State, strings: StaffStrings): String {
    val checkIn = "${strings.checkedInLabel} ${state.today?.checkIn.orEmpty()}"
    val checkOut = state.today?.checkOut?.let { " · ${strings.checkedOutLabel} $it" }.orEmpty()
    return checkIn + checkOut
}
