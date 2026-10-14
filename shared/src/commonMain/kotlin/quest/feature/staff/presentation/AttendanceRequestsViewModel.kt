package quest.feature.staff.presentation

import androidx.lifecycle.viewModelScope
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.staff.AttendanceRequestType
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CorrectionKind
import quest.api.staff.NewAttendanceRequest
import quest.core.mvi.MviEffect
import quest.core.mvi.MviIntent
import quest.core.mvi.MviState
import quest.core.mvi.MviViewModel
import quest.core.platform.Today
import quest.feature.chat.domain.AttachmentRefusal
import quest.feature.chat.domain.PickedFile
import quest.feature.chat.domain.UploadStaging
import quest.feature.chat.presentation.AttachmentDraft
import quest.feature.chat.presentation.AttachmentDrafts
import quest.feature.chat.presentation.DraftsState
import quest.feature.staff.domain.StaffGateway

/**
 * Feature 18, what the request form says after Send — as data rather than as a sentence, exactly as
 * [CheckInMessage] is: one table of words ([StaffStrings.requestMessage]) reads it in Arabic and in English, and a
 * test asserts the outcome without reading either.
 */
sealed interface RequestMessage {
    data object Sent : RequestMessage
    data object Failed : RequestMessage
    data object Offline : RequestMessage
    data object ReasonNeeded : RequestMessage
    data object DateInvalid : RequestMessage
    /** A remote day is for today or later. */
    data object DateNotPast : RequestMessage
    /** A correction is for today or earlier. */
    data object DateNotFuture : RequestMessage
    data object TimeNeeded : RequestMessage
}

/**
 * Feature 18 on the phone: "طلب يوم عن بعد" from the Today card and "طلب تصحيح" from a day of My record, plus the
 * list of her own requests with what became of each.
 *
 * **The form is validated here only as far as the employee's own typing**: a reason, a date that is the right side of
 * today, and a time where the kind needs one. Everything else is the server's — whether she already has a request for
 * that day, who her direct manager is, and whether the day may be corrected at all.
 */
object AttendanceRequestsContract {
    /** The sheet, while it is open: one form serves both types, and [type] says which fields matter. */
    data class Form(
        val type: String,
        val date: String,
        val reason: String = "",
        val correctionKind: String = CorrectionKind.FORGOT_CHECK_IN,
        val checkIn: String = "",
        val checkOut: String = "",
    ) {
        val correction: Boolean get() = type == AttendanceRequestType.CORRECTION
    }

    data class State(
        val loading: Boolean = true,
        val requests: List<AttendanceRequestView> = emptyList(),
        val failed: Boolean = false,
        val form: Form? = null,
        val submitting: Boolean = false,
        /** At most one file — the contract carries one `attachmentId` — uploaded the moment it is picked. */
        val drafts: List<AttachmentDraft> = emptyList(),
        val refusal: AttachmentRefusal? = null,
        val message: RequestMessage? = null,
    ) : MviState {
        /** Send waits for every picked file to be up, so the request never names an attachment the server lacks. */
        val canSubmit: Boolean get() = form != null && !submitting && drafts.all { it.ref != null }
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        /** The Today card's action: tomorrow by default, as the spec words it. */
        data object OpenRemoteDay : Intent
        data class OpenCorrection(val date: String) : Intent
        data object Close : Intent
        data class SetDate(val text: String) : Intent
        data class SetReason(val text: String) : Intent
        data class SetCorrectionKind(val kind: String) : Intent
        data class SetCheckIn(val text: String) : Intent
        data class SetCheckOut(val text: String) : Intent
        data object Submit : Intent
        data object DismissMessage : Intent
        data class AddFiles(val files: List<PickedFile>) : Intent
        data class RemoveDraft(val localId: String) : Intent
        data class RetryDraft(val localId: String) : Intent
    }

    sealed interface Effect : MviEffect
}

class AttendanceRequestsViewModel(
    private val api: StaffGateway,
    staging: UploadStaging,
    /** The school's day as the device knows it — only ever to pre-fill and to refuse the wrong side of today. */
    private val today: () -> LocalDate = { Today.date() },
) : MviViewModel<AttendanceRequestsContract.State, AttendanceRequestsContract.Intent, AttendanceRequestsContract.Effect>(
    AttendanceRequestsContract.State(),
) {

    /** M7's attachment pipeline, to feature 18's own upload route: re-encode, strip, stage, stream, clean up. */
    private val files = AttachmentDrafts(
        scope = viewModelScope,
        staging = staging,
        upload = { file, onProgress -> api.uploadRequestAttachment(file, onProgress) },
        read = { DraftsState(current.drafts, current.refusal) },
        write = { change -> reduce { val next = DraftsState(drafts, refusal).change(); copy(drafts = next.drafts, refusal = next.refusal) } },
    )

    override fun onCleared() {
        files.clear()
        super.onCleared()
    }

    override suspend fun handle(intent: AttendanceRequestsContract.Intent) {
        when (intent) {
            AttendanceRequestsContract.Intent.Load -> load()
            AttendanceRequestsContract.Intent.OpenRemoteDay -> open(
                AttendanceRequestsContract.Form(
                    type = AttendanceRequestType.REMOTE_DAY,
                    date = today().plus(1, DateTimeUnit.DAY).toString(),
                ),
            )
            is AttendanceRequestsContract.Intent.OpenCorrection -> open(
                AttendanceRequestsContract.Form(type = AttendanceRequestType.CORRECTION, date = intent.date),
            )
            AttendanceRequestsContract.Intent.Close -> {
                files.clear()
                reduce { copy(form = null, drafts = emptyList(), refusal = null, message = null) }
            }
            is AttendanceRequestsContract.Intent.SetDate -> form { copy(date = intent.text.trim()) }
            is AttendanceRequestsContract.Intent.SetReason -> form { copy(reason = intent.text.take(REASON_MAX)) }
            is AttendanceRequestsContract.Intent.SetCorrectionKind -> form { copy(correctionKind = intent.kind) }
            is AttendanceRequestsContract.Intent.SetCheckIn -> form { copy(checkIn = intent.text.trim()) }
            is AttendanceRequestsContract.Intent.SetCheckOut -> form { copy(checkOut = intent.text.trim()) }
            AttendanceRequestsContract.Intent.Submit -> submit()
            AttendanceRequestsContract.Intent.DismissMessage -> reduce { copy(message = null) }
            is AttendanceRequestsContract.Intent.AddFiles -> files.add(intent.files)
            is AttendanceRequestsContract.Intent.RemoveDraft -> files.remove(intent.localId)
            is AttendanceRequestsContract.Intent.RetryDraft -> files.retry(intent.localId)
        }
    }

    private suspend fun load() {
        reduce { copy(loading = true, failed = false) }
        try {
            val answer = api.myAttendanceRequests()
            reduce { copy(loading = false, requests = answer.requests, failed = false) }
        } catch (_: Throwable) {
            reduce { copy(loading = false, failed = true) }
        }
    }

    private suspend fun open(form: AttendanceRequestsContract.Form) {
        files.clear()
        reduce { copy(form = form, drafts = emptyList(), refusal = null, message = null) }
    }

    private suspend fun form(change: AttendanceRequestsContract.Form.() -> AttendanceRequestsContract.Form) =
        reduce { copy(form = form?.change(), message = null) }

    private suspend fun submit() {
        val form = current.form ?: return
        if (!current.canSubmit) return
        refusal(form)?.let { reason ->
            reduce { copy(message = reason) }
            return
        }
        reduce { copy(submitting = true, message = null) }
        val request = NewAttendanceRequest(
            type = form.type,
            date = form.date,
            reason = form.reason.trim(),
            correctionKind = if (form.correction) form.correctionKind else null,
            checkIn = form.checkIn.takeIf { form.correction && it.isNotBlank() },
            checkOut = form.checkOut.takeIf { form.correction && it.isNotBlank() },
            attachmentId = current.drafts.firstNotNullOfOrNull { it.ref?.id },
        )
        try {
            api.createAttendanceRequest(request)
            files.sent()
            reduce { copy(submitting = false, form = null, drafts = emptyList(), refusal = null, message = RequestMessage.Sent) }
            load()
        } catch (e: ApiException) {
            reduce { copy(submitting = false, message = if (e.error.code == ApiError.NETWORK) RequestMessage.Offline else RequestMessage.Failed) }
        } catch (_: Throwable) {
            reduce { copy(submitting = false, message = RequestMessage.Offline) }
        }
    }

    /** Why this form cannot go yet, or null when only the server can say. */
    private fun refusal(form: AttendanceRequestsContract.Form): RequestMessage? {
        if (form.reason.isBlank()) return RequestMessage.ReasonNeeded
        val date = runCatching { LocalDate.parse(form.date) }.getOrNull() ?: return RequestMessage.DateInvalid
        val now = today()
        if (!form.correction) return if (date < now) RequestMessage.DateNotPast else null
        if (date > now) return RequestMessage.DateNotFuture
        val checkIn = form.checkIn.takeIf { it.isNotBlank() }
        val checkOut = form.checkOut.takeIf { it.isNotBlank() }
        if (listOfNotNull(checkIn, checkOut).any { !isClock(it) }) return RequestMessage.TimeNeeded
        return when (form.correctionKind) {
            CorrectionKind.FORGOT_CHECK_IN -> if (checkIn == null) RequestMessage.TimeNeeded else null
            CorrectionKind.FORGOT_CHECK_OUT -> if (checkOut == null) RequestMessage.TimeNeeded else null
            else -> if (checkIn == null && checkOut == null) RequestMessage.TimeNeeded else null
        }
    }

    companion object {
        /** The contract's cap on a reason. */
        const val REASON_MAX = 500

        /** `HH:mm`, the only shape the contract takes. */
        fun isClock(value: String): Boolean {
            val parts = value.split(":")
            if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return false
            val hour = parts[0].toIntOrNull() ?: return false
            val minute = parts[1].toIntOrNull() ?: return false
            return hour in 0..23 && minute in 0..59
        }
    }
}
