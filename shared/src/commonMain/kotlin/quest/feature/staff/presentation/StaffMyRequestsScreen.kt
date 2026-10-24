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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import quest.feature.parent.presentation.ParentButton
import quest.feature.parent.presentation.Strings
import quest.feature.school.domain.Flags
import quest.feature.school.presentation.GateFallback
import quest.feature.school.presentation.featureEnabled
import quest.ui.design.AnimatedDotsLoader
import quest.ui.design.Dimens

/**
 * Feature 34's "طلباتي / My requests": every request she has made — leave, permission, a remote day or a correction —
 * with its status, the step a pending one waits for, a rejection's reason, and Cancel while nobody has decided it.
 * The two new forms open from here too, so a decision push lands somewhere she can act.
 */
@Composable
fun StaffMyRequestsRoute(onBack: () -> Unit) {
    val vm: AttendanceRequestsViewModel = koinViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.dispatch(AttendanceRequestsContract.Intent.Load) }
    StaffShell(title = { it.myRequests }, currentTab = null, onTabSelected = {}, onBack = onBack) { s, parent ->
        GateFallback(Flags.STAFF_LEAVE, onClosed = onBack)
        StaffMyRequestsScreen(state, s, parent, vm::dispatch)
    }
}

@Composable
fun StaffMyRequestsScreen(
    state: AttendanceRequestsContract.State,
    strings: StaffStrings,
    parentStrings: Strings = Strings.en,
    dispatch: (AttendanceRequestsContract.Intent) -> Unit = {},
) {
    // Both switches, as the server's routes ask: a school without check-in has no attendance for a leave to excuse.
    if (featureEnabled(Flags.STAFF_ATTENDANCE) && featureEnabled(Flags.STAFF_LEAVE)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.s16, vertical = Dimens.s8)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(Dimens.s8)) {
                ParentButton(
                    text = strings.requestLeave,
                    onClick = { dispatch(AttendanceRequestsContract.Intent.OpenLeave) },
                    primary = false,
                    icon = "🗓",
                    modifier = Modifier.weight(1f),
                )
                ParentButton(
                    text = strings.requestPermission,
                    onClick = { dispatch(AttendanceRequestsContract.Intent.OpenPermission) },
                    primary = false,
                    icon = "⏱",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(Dimens.s8))
            if (state.loading) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    AnimatedDotsLoader(dotSize = 12.dp, spacing = 8.dp)
                }
            } else {
                MyRequestsSection(
                    state, strings,
                    onDismissMessage = { dispatch(AttendanceRequestsContract.Intent.DismissMessage) },
                    onCancel = { id -> dispatch(AttendanceRequestsContract.Intent.Cancel(id)) },
                    title = false,
                )
            }
            Spacer(Modifier.height(Dimens.s16))
        }
    }
    AttendanceRequestDialog(state, strings, parentStrings, dispatch)
}
