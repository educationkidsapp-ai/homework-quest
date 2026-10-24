package quest.feature.staff.presentation

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import quest.api.ApiException
import quest.api.dto.ApiError
import quest.api.staff.AttendanceRequestStatus
import quest.api.staff.AttendanceRequestType
import quest.api.staff.AttendanceRequestView
import quest.api.staff.CorrectionKind
import quest.api.staff.LeavePreview
import quest.api.staff.LeaveType
import quest.api.staff.MyLeaveBalance
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

    // ---- feature 34: leave and permission

    /** The last day of a leave is before its first. */
    data object RangeInvalid : RequestMessage
    /** The type's document rule asks for a photo or a PDF and she has attached none. */
    data object DocumentNeeded : RequestMessage
    /** A permission's end is not after its start. */
    data object TimeRangeInvalid : RequestMessage
    /** Longer than the school's own limit for one day (`MyLeaveBalance.permissionMaxMinutes`). */
    data object PermissionTooLong : RequestMessage
    /** 409 `over_balance`: the range is longer than what is left of her allowance for that type. */
    data object OverBalance : RequestMessage
    /** 409 `overlaps`: she already has an approved request on one of those days. */
    data object Overlaps : RequestMessage
    /** The pending request she withdrew. */
    data object Cancelled : RequestMessage
}

/**
 * Feature 18 on the phone: "طلب يوم عن بعد" from the Today card and "طلب تصحيح" from a day of My record, plus the
 * list of her own requests with what became of each. Feature 34 puts "طلب إجازة" and "طلب استئذان" through the same
 * form, and Cancel on the same list.
 *
 * **The form is validated here only as far as the employee's own typing**: a reason, a date on the right side of
 * today, a range that runs forwards, a time where the kind needs one, and the document the type asks for. Everything
 * else is the server's — her balance, the overlap with her other requests, who her direct manager is, and whether the
 * day may be corrected at all.
 */
object AttendanceRequestsContract {
    /** The sheet, while it is open: one form serves every type, and [type] says which fields matter. */
    data class Form(
        val type: String,
        val date: String,
        val reason: String = "",
        val correctionKind: String = CorrectionKind.FORGOT_CHECK_IN,
        val checkIn: String = "",
        val checkOut: String = "",
        /** Feature 34: the kind of leave, the last day of its range, and a permission's two times. */
        val leaveType: String = LeaveType.ANNUAL,
        val toDate: String = "",
        val fromTime: String = "",
        val toTime: String = "",
    ) {
        val correction: Boolean get() = type == AttendanceRequestType.CORRECTION
        val leave: Boolean get() = type == AttendanceRequestType.LEAVE
        val permission: Boolean get() = type == AttendanceRequestType.PERMISSION

        /** The last day of a leave: the first one again while she has not widened the range. */
        val lastDay: String get() = toDate.takeIf { it.isNotBlank() } ?: date
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
        /**
         * Feature 34: `GET /staff/attendance/leave/balance` — which types her school offers at all, its approval
         * chain and its permission limit. Read when the first leave or permission form opens, and null while the
         * school has the switch off or the request has not answered yet.
         */
        val balance: MyLeaveBalance? = null,
        /** Feature 34: the days the range would use and the balance it would leave, as the server counts them. */
        val preview: LeavePreview? = null,
        val previewing: Boolean = false,
        /** The pending request whose Cancel is in flight. */
        val cancelling: String? = null,
    ) : MviState {
        /** Send waits for every picked file to be up, so the request never names an attachment the server lacks. */
        val canSubmit: Boolean get() = form != null && !submitting && drafts.all { it.ref != null }

        /** The leave types the school offers, in the contract's own order; empty until the balance has been read. */
        val leaveTypes: List<String>
            get() = balance?.types?.filter { it.enabled }?.map { it.type } ?: emptyList()
    }

    sealed interface Intent : MviIntent {
        data object Load : Intent
        /** The Today card's action: tomorrow by default, as the spec words it. */
        data object OpenRemoteDay : Intent
        data class OpenCorrection(val date: String) : Intent
        /** Feature 34: today, her first enabled type, and the balance behind both. */
        data object OpenLeave : Intent
        data object OpenPermission : Intent
        data object Close : Intent
        data class SetDate(val text: String) : Intent
        data class SetReason(val text: String) : Intent
        data class SetCorrectionKind(val kind: String) : Intent
        data class SetCheckIn(val text: String) : Intent
        data class SetCheckOut(val text: String) : Intent
        data class SetLeaveType(val type: String) : Intent
        data class SetToDate(val text: String) : Intent
        data class SetFromTime(val text: String) : Intent
        data class SetToTime(val text: String) : Intent
        data object Submit : Intent
        /** Feature 34: the author withdraws her own pending request. */
        data class Cancel(val id: String) : Intent
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

    /** Only the newest preview matters: a second keystroke cancels the request the first one started. */
    private var previewJob: Job? = null

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
            AttendanceRequestsContract.Intent.OpenLeave -> {
                val balance = balance()
                val day = today().toString()
                open(
                    AttendanceRequestsContract.Form(
                        type = AttendanceRequestType.LEAVE,
                        date = day,
                        toDate = day,
                        leaveType = balance?.types?.firstOrNull { it.enabled }?.type ?: LeaveType.ANNUAL,
                    ),
                )
                preview()
            }
            AttendanceRequestsContract.Intent.OpenPermission -> {
                balance()
                open(AttendanceRequestsContract.Form(type = AttendanceRequestType.PERMISSION, date = today().toString()))
            }
            AttendanceRequestsContract.Intent.Close -> {
                files.clear()
                previewJob?.cancel()
                reduce { copy(form = null, drafts = emptyList(), refusal = null, message = null, preview = null, previewing = false) }
            }
            is AttendanceRequestsContract.Intent.SetDate -> { form { copy(date = intent.text.trim()) }; preview() }
            is AttendanceRequestsContract.Intent.SetReason -> form { copy(reason = intent.text.take(REASON_MAX)) }
            is AttendanceRequestsContract.Intent.SetCorrectionKind -> form { copy(correctionKind = intent.kind) }
            is AttendanceRequestsContract.Intent.SetCheckIn -> form { copy(checkIn = intent.text.trim()) }
            is AttendanceRequestsContract.Intent.SetCheckOut -> form { copy(checkOut = intent.text.trim()) }
            is AttendanceRequestsContract.Intent.SetLeaveType -> { form { copy(leaveType = intent.type) }; preview() }
            is AttendanceRequestsContract.Intent.SetToDate -> { form { copy(toDate = intent.text.trim()) }; preview() }
            is AttendanceRequestsContract.Intent.SetFromTime -> form { copy(fromTime = intent.text.trim()) }
            is AttendanceRequestsContract.Intent.SetToTime -> form { copy(toTime = intent.text.trim()) }
            AttendanceRequestsContract.Intent.Submit -> submit()
            is AttendanceRequestsContract.Intent.Cancel -> cancel(intent.id)
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

    /**
     * Feature 34: her allowance, read once and kept — the form has to know which types the school offers and how long
     * a permission may be before she can fill either in. A school with the switch off answers 404; the forms then
     * show the contract's own type list and no numbers, and the server refuses the send.
     */
    private suspend fun balance(): MyLeaveBalance? {
        current.balance?.let { return it }
        val answer = runCatching { api.myLeaveBalance() }.getOrNull()
        answer?.let { found -> reduce { copy(balance = found) } }
        return answer
    }

    /** The days the range would use, as the server counts her own schedule — never guessed on the phone. */
    private fun preview() {
        val form = current.form ?: return
        if (!form.leave) return
        previewJob?.cancel()
        val from = runCatching { LocalDate.parse(form.date) }.getOrNull()
        val to = runCatching { LocalDate.parse(form.lastDay) }.getOrNull()
        if (from == null || to == null || to < from) {
            reduce { copy(preview = null, previewing = false) }
            return
        }
        reduce { copy(previewing = true) }
        previewJob = launch {
            val answer = runCatching { api.leavePreview(form.leaveType, from.toString(), to.toString()) }.getOrNull()
            reduce { if (this.form?.leave == true) copy(preview = answer, previewing = false) else copy(previewing = false) }
        }
    }

    private suspend fun open(form: AttendanceRequestsContract.Form) {
        files.clear()
        previewJob?.cancel()
        reduce { copy(form = form, drafts = emptyList(), refusal = null, message = null, preview = null, previewing = false) }
    }

    private fun form(change: AttendanceRequestsContract.Form.() -> AttendanceRequestsContract.Form) =
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
            leaveType = form.leaveType.takeIf { form.leave },
            toDate = form.lastDay.takeIf { form.leave },
            fromTime = form.fromTime.takeIf { form.permission },
            toTime = form.toTime.takeIf { form.permission },
        )
        try {
            api.createAttendanceRequest(request)
            files.sent()
            reduce {
                copy(
                    submitting = false, form = null, drafts = emptyList(), refusal = null,
                    preview = null, previewing = false, message = RequestMessage.Sent,
                )
            }
            load()
        } catch (e: ApiException) {
            reduce { copy(submitting = false, message = failure(e)) }
        } catch (_: Throwable) {
            reduce { copy(submitting = false, message = RequestMessage.Offline) }
        }
    }

    /** Feature 34: the author withdraws a request of hers that nobody has decided yet. */
    private suspend fun cancel(id: String) {
        if (current.cancelling != null) return
        reduce { copy(cancelling = id, message = null) }
        try {
            api.cancelAttendanceRequest(id)
            reduce { copy(cancelling = null, message = RequestMessage.Cancelled) }
            load()
        } catch (e: ApiException) {
            reduce { copy(cancelling = null, message = if (e.error.code == ApiError.NETWORK) RequestMessage.Offline else RequestMessage.Failed) }
        } catch (_: Throwable) {
            reduce { copy(cancelling = null, message = RequestMessage.Offline) }
        }
    }

    /** Why this form cannot go yet, or null when only the server can say. */
    private fun refusal(form: AttendanceRequestsContract.Form): RequestMessage? {
        if (form.reason.isBlank()) return RequestMessage.ReasonNeeded
        val date = runCatching { LocalDate.parse(form.date) }.getOrNull() ?: return RequestMessage.DateInvalid
        val now = today()
        if (form.leave) {
            val last = runCatching { LocalDate.parse(form.lastDay) }.getOrNull() ?: return RequestMessage.DateInvalid
            if (last < date) return RequestMessage.RangeInvalid
            // The document rule is the server's answer, not a guess: the preview says whether this very range needs one.
            if (current.preview?.documentRequired == true && current.drafts.none { it.ref != null }) return RequestMessage.DocumentNeeded
            return null
        }
        if (form.permission) {
            if (!isClock(form.fromTime) || !isClock(form.toTime)) return RequestMessage.TimeNeeded
            val minutes = minutesOf(form.toTime) - minutesOf(form.fromTime)
            if (minutes <= 0) return RequestMessage.TimeRangeInvalid
            val limit = current.balance?.permissionMaxMinutes ?: return null
            return if (minutes > limit) RequestMessage.PermissionTooLong else null
        }
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

        /** 409 from `POST /staff/attendance/requests`: the balance would go below zero. */
        const val OVER_BALANCE = "over_balance"

        /** 409 from the same route: one of those days is already covered by an approved request of hers. */
        const val OVERLAPS = "overlaps"

        /** `HH:mm`, the only shape the contract takes. */
        fun isClock(value: String): Boolean {
            val parts = value.split(":")
            if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return false
            val hour = parts[0].toIntOrNull() ?: return false
            val minute = parts[1].toIntOrNull() ?: return false
            return hour in 0..23 && minute in 0..59
        }

        /** Minutes since midnight of an `HH:mm` the form has already accepted. */
        fun minutesOf(value: String): Int {
            val parts = value.split(":")
            return (parts[0].toInt() * 60) + parts[1].toInt()
        }

        /**
         * Feature 34: the server's own two refusals, worded as the spec names them — over the balance, and
         * overlapping an approved request — so the form says which it was rather than "try again".
         */
        fun failure(e: ApiException): RequestMessage = when (e.error.code) {
            ApiError.NETWORK -> RequestMessage.Offline
            OVER_BALANCE -> RequestMessage.OverBalance
            OVERLAPS -> RequestMessage.Overlaps
            else -> RequestMessage.Failed
        }

        /** A request whose author may still withdraw it. */
        fun cancellable(request: AttendanceRequestView): Boolean = request.status == AttendanceRequestStatus.PENDING
    }
}
