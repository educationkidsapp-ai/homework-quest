// hq-flag: none (feature 18's form and list are drawn inside the Today card and My record, both already behind FeatureGate(staffAttendance))
package quest.feature.staff.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
 * Feature 18's one form, for both requests: a remote day (the date and why) and a correction (which of the three
 * things happened, the time she claims, why, and a photo or a PDF if she has one). The words are the staff table's and
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
                if (form.correction) strings.requestCorrection else strings.requestRemoteDay,
                color = DashboardTokens.inkStrong,
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (form.correction) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s8), modifier = Modifier.fillMaxWidth()) {
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
                OutlinedTextField(
                    value = form.date,
                    onValueChange = { dispatch(AttendanceRequestsContract.Intent.SetDate(it)) },
                    label = { Text(strings.requestDate, color = DashboardTokens.inkSoft) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
                if (form.correction) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // One file is all the request carries, so the picker closes once she has picked it.
                        AttachMenuButton(
                            already = MAX_ATTACHMENTS - 1 + state.drafts.size,
                            strings = parentStrings,
                            onPick = { dispatch(AttendanceRequestsContract.Intent.AddFiles(it)) },
                        )
                        Text(strings.requestAttach, style = MaterialTheme.typography.bodySmall, color = DashboardTokens.inkSoft)
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
 * "طلباتي / My requests" on My record: what she asked for, for which day, and what became of it — with the name of
 * whoever decided, which is the audit the spec asks every correction to carry.
 */
@Composable
fun MyRequestsSection(
    state: AttendanceRequestsContract.State,
    strings: StaffStrings,
    onDismissMessage: () -> Unit = {},
) {
    SectionTitle(strings.myRequests)
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
    state.requests.forEach { request -> RequestRow(request, strings) }
}

@Composable
private fun RequestRow(request: AttendanceRequestView, strings: StaffStrings) {
    ParentCard(Modifier.fillMaxWidth().padding(bottom = Dimens.s8)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${strings.requestTypeName(request.type)} · ${request.date}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = DashboardTokens.ink,
                )
                Spacer(Modifier.height(Dimens.s4))
                Text(
                    buildString {
                        request.correctionKind?.let { append(strings.correctionKindName(it)) }
                        listOfNotNull(
                            request.checkIn?.let { "${strings.checkedInLabel} $it" },
                            request.checkOut?.let { "${strings.checkedOutLabel} $it" },
                        ).forEach { if (isNotEmpty()) append(" · "); append(it) }
                        request.decidedBy?.let {
                            if (isNotEmpty()) append(" · ")
                            append(strings.requestDecidedBy.replace("{name}", it))
                        }
                    }.ifBlank { request.reason },
                    style = MaterialTheme.typography.bodySmall,
                    color = DashboardTokens.inkSoft,
                )
            }
            Chip(strings.requestStatusName(request.status), DashboardTokens.secondarySoft)
        }
    }
}
