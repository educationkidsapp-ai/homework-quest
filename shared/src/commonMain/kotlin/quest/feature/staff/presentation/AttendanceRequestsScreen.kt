// hq-flag: none (feature 18's form and list are drawn inside the Today card, My record and the two feature-34 screens, all already behind FeatureGate)
package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CorrectionKind
import quest.api.staff.LeaveType
import quest.feature.chat.domain.MAX_ATTACHMENTS
import quest.feature.chat.presentation.AttachMenuButton
import quest.feature.chat.presentation.DraftTray
import quest.feature.parent.presentation.Chip
import quest.feature.parent.presentation.ParentCard
import quest.feature.parent.presentation.SectionTitle
import quest.feature.parent.presentation.Strings
import quest.ui.design.DashboardFilterChip
import quest.ui.design.DashboardTokens
import quest.ui.design.Dimens

/**
 * One form for all four requests: a remote day (the date and why), a correction (which of the three things happened,
 * the time she claims, why, and a photo or a PDF if she has one), feature 34's leave (the type, the two dates, why,
 * and the document the rule asks for) and its permission (the day and two times). The words are the staff table's and
 * the validation is the view model's, so this file draws and nothing more.
 */
@Composable
fun AttendanceRequestDialog(
    state: AttendanceRequestsContract.State,
    strings: StaffStrings,
    parentStrings: Strings,
    dispatch: (AttendanceRequestsContract.Intent) -> Unit,
) {
    val form = state.form ?: return
    AlertDialog(
        onDismissRequest = { dispatch(AttendanceRequestsContract.Intent.Close) },
        title = {
            Text(
                when {
                    form.correction -> strings.requestCorrection
                    form.leave -> strings.requestLeave
                    form.permission -> strings.requestPermission
                    else -> strings.requestRemoteDay
                },
                color = DashboardTokens.inkStrong,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (form.correction) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.s8),
                        verticalArrangement = Arrangement.spacedBy(Dimens.s8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        listOf(CorrectionKind.FORGOT_CHECK_IN, CorrectionKind.FORGOT_CHECK_OUT, CorrectionKind.PHONE_PROBLEM).forEach { kind ->
                            DashboardFilterChip(
                                strings.correctionKindName(kind),
                                selected = kind == form.correctionKind,
                                onClick = { dispatch(AttendanceRequestsContract.Intent.SetCorrectionKind(kind)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.s8))
                }
                if (form.leave) {
                    // The types the school offers, as the balance answered; the contract's own list while it has not.
                    // Five chips do not fit one line of a dialog in either language, so they wrap rather than squash.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Dimens.s8),
                        verticalArrangement = Arrangement.spacedBy(Dimens.s8),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        state.leaveTypes.ifEmpty { LeaveType.ALL }.forEach { type ->
                            DashboardFilterChip(
                                strings.leaveTypeName(type),
                                selected = type == form.leaveType,
                                onClick = { dispatch(AttendanceRequestsContract.Intent.SetLeaveType(type)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(Dimens.s8))
                }
                OutlinedTextField(
                    value = form.date,
                    onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetDate(it)) },
                    label = { Text(if (form.leave) strings.requestFromDate else strings.requestDate, color = DashboardTokens.inkSoft) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (form.leave) {
                    Spacer(Modifier.height(Dimens.s8))
                    OutlinedTextField(
                        value = form.toDate,
                        onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetToDate(it)) },
                        label = { Text(strings.requestToDate, color = DashboardTokens.inkSoft) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (form.permission) {
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        OutlinedTextField(
                            value = form.fromTime,
                            onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetFromTime(it)) },
                            label = { Text(strings.requestFromTime, color = DashboardTokens.inkSoft) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.toTime,
                            onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetToTime(it)) },
                            label = { Text(strings.requestToTime, color = DashboardTokens.inkSoft) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    state.balance?.let { balance ->
                        Text(
                            strings.permissionLimit.replace("{n}", balance.permissionMaxMinutes.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardTokens.inkSoft,
                        )
                    }
                }
                if (form.correction) {
                    Spacer(Modifier.height(Dimens.s8))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8)) {
                        OutlinedTextField(
                            value = form.checkIn,
                            onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetCheckIn(it)) },
                            label = { Text(strings.checkedInLabel, color = DashboardTokens.inkSoft) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = form.checkOut,
                            onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetCheckOut(it)) },
                            label = { Text(strings.checkedOutLabel, color = DashboardTokens.inkSoft) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(Dimens.s8))
                OutlinedTextField(
                    value = form.reason,
                    onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetReason(it)) },
                    label = { Text(strings.requestReason, color = DashboardTokens.inkSoft) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
                )
                // Feature 34: the days the range uses and what it leaves of her balance, before she sends it.
                if (form.leave) {
                    Spacer(Modifier.height(Dimens.s8))
                    Text(
                        strings.previewLine(state.preview, state.previewing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DashboardTokens.ink,
                    )
                }
                if (form.correction || form.leave) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // One file is all the request carries, so the picker closes once she has picked it.
                        AttachMenuButton(
                            already = MAX_ATTACHMENTS - 1 + state.drafts.size,
                            strings = parentStrings,
                            onPick = { dispatch(AttendanceRequestsContract.Intent.AddFiles(it)) },
                        )
                        Text(
                            if (state.preview?.documentRequired == true) strings.requestAttachRequired else strings.requestAttach,
                            style = MaterialTheme.typography.bodySmall,
                            color = DashboardTokens.inkSoft,
                        )
                    }
                    DraftTray(
                        state.drafts, state.refusal, parentStrings,
                        onRemove = { dispatch(AttendanceRequestsContract.Intent.RemoveDraft(it)) },
                        onRetry = { dispatch(AttendanceRequestsContract.Intent.RetryDraft(it)) },
                    )
                }
                state.message?.let { message ->
                    Spacer(Modifier.height(Dimens.s8))
                    Text(strings.requestMessage(message), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { dispatch(AttendanceRequestsContract.Intent.Submit) },
                enabled = state.canSubmit,
            ) { Text(strings.requestSend) }
        },
        dismissButton = {
            TextButton(onClick = { dispatch(AttendanceRequestsContract.Intent.Close) }) { Text(strings.cancel) }
        },
    )
}

/**
 * "طلباتي / My requests": what she asked for, for which day, and what became of it — with the name of whoever
 * decided, which is the audit the spec asks every correction to carry, feature 34's rejection reason, the step a
 * pending request waits for, and Cancel while nobody has decided it.
 */
@Composable
fun MyRequestsSection(
    state: AttendanceRequestsContract.State,
    strings: StaffStrings,
    onDismissMessage: () -> Unit = {},
    onCancel: ((String) -> Unit)? = null,
    title: Boolean = true,
) {
    if (title) SectionTitle(strings.myRequests)
    if (state.form == null) {
        state.message?.let { message ->
            ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8), onClick = onDismissMessage) {
                Text(strings.requestMessage(message), style = MaterialTheme.typography.bodyMedium, color = DashboardTokens.ink)
            }
        }
    }
    if (state.requests.isEmpty()) {
        ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
            Text(
                if (state.failed) strings.recordFailed else strings.noRequests,
                style = MaterialTheme.typography.bodyMedium,
                color = DashboardTokens.inkSoft,
            )
        }
        return
    }
    state.requests.forEach { request ->
        RequestRow(
            request, strings,
            onCancel = onCancel?.takeIf { AttendanceRequestsViewModel.cancellable(request) }?.let { cancel -> { cancel(request.id) } },
            cancelling = state.cancelling == request.id,
        )
    }
}

@Composable
private fun RequestRow(
    request: AttendanceRequestView,
    strings: StaffStrings,
    onCancel: (() -> Unit)? = null,
    cancelling: Boolean = false,
) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${strings.requestTypeName(request.type)} · ${strings.requestWhen(request)}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.ink,
                )
                Spacer(Modifier.height(Dimens.s4))
                Text(
                    buildString {
                        request.leaveType?.let { append(strings.leaveTypeName(it)) }
                        listOfNotNull(
                            request.correctionKind?.let { strings.correctionKindName(it) },
                            request.checkIn?.let { "${strings.checkedInLabel} $it" },
                            request.checkOut?.let { "${strings.checkedOutLabel} $it" },
                            request.days?.let { strings.leaveDaysLabel.replace("{n}", it.toString()) },
                            request.minutes?.let { strings.permissionMinutesLabel.replace("{n}", it.toString()) },
                            request.stage?.let { strings.stageName(it) },
                            request.decidedBy?.let { strings.requestDecidedBy.replace("{name}", it) },
                        ).forEach { if (isNotEmpty()) append(" · "); append(it) }
                    }.ifBlank { request.reason },
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
                // Feature 34: a rejection carries its reason, which is the whole point of asking for one.
                request.rejectReason?.takeIf { it.isNotBlank() }?.let { reason ->
                    Spacer(Modifier.height(Dimens.s4))
                    Text(
                        strings.rejectionReason.replace("{reason}", reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = DashboardTokens.inkSoft,
                    )
                }
                onCancel?.let { cancel ->
                    TextButton(onClick = cancel, enabled = !cancelling) {
                        Text(strings.cancelRequest, color = DashboardTokens.accentInk)
                    }
                }
            }
            Chip(strings.requestStatusName(request.status), DashboardTokens.secondarySoft)
        }
    }
}
