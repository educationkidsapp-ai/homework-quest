package quest.api.staff

import kotlinx.serialization.Serializable
import quest.api.dto.AttachmentRef

/**
 * Feature 18 `remote-days-corrections` (docs/features/18-remote-days-corrections.md): staff check in from anywhere on
 * an approved remote day (record mode [CheckInMode.REMOTE]), and a missed check-in or check-out is fixed by a request
 * the direct manager approves (mode [CheckInMode.CORRECTION]) — nobody edits a record by hand. Behind `staffAttendance`.
 *
 * The Admin's **school-wide** remote day moved to feature 23's [RemoteStudyDay]; the per-employee request below is
 * unchanged by it.
 *
 * **Who decides.** The employee's direct manager as feature 14 resolves it (direct → academy director → the Admins),
 * or any Admin. Nobody else, and never the employee herself.
 *
 * **What stays.** An approved correction writes the claimed time onto the day's record with who approved it and when;
 * the times it replaced are kept on the record ([AttendanceRequestView.originalCheckIn]) and every check-in attempt
 * stays in the log. A rejected request changes nothing.
 */

/**
 * Wire words of [AttendanceRequestView.type]. Feature 34 adds the two the staff app asks for from the Attendance
 * tab: [LEAVE] (a range of days of one [LeaveType]) and [PERMISSION] (part of one day, استئذان).
 */
object AttendanceRequestType {
    const val REMOTE_DAY = "REMOTE_DAY"
    const val CORRECTION = "CORRECTION"
    const val LEAVE = "LEAVE"
    const val PERMISSION = "PERMISSION"
}

/**
 * Feature 34: the five kinds of leave, each of which the school can turn off and give a yearly balance per staff
 * category. [UNPAID] and [OTHER] have no balance unless the Admin sets one.
 */
object LeaveType {
    const val ANNUAL = "ANNUAL"
    const val SICK = "SICK"
    const val EMERGENCY = "EMERGENCY"
    const val UNPAID = "UNPAID"
    const val OTHER = "OTHER"
    val ALL = listOf(ANNUAL, SICK, EMERGENCY, UNPAID, OTHER)
}

/** Feature 34: the school's approval chain for leave and permission (`attendanceSettings.leaveChain`). */
object LeaveChain {
    /** Her direct manager decides, and that is the decision. */
    const val MANAGER = "MANAGER"
    /** Her direct manager decides first, then the Admin; it counts only once the Admin has approved. */
    const val MANAGER_THEN_ADMIN = "MANAGER_THEN_ADMIN"
}

/**
 * Feature 34: which step of the chain a pending request is waiting for ([AttendanceRequestView.stage]). A request for
 * a **past** date starts at [ADMIN] whatever the chain says.
 */
object RequestStage {
    const val MANAGER = "MANAGER"
    const val ADMIN = "ADMIN"
}

/** Wire words of a correction's [AttendanceRequestView.correctionKind]. */
object CorrectionKind {
    const val FORGOT_CHECK_IN = "FORGOT_CHECK_IN"
    const val FORGOT_CHECK_OUT = "FORGOT_CHECK_OUT"
    const val PHONE_PROBLEM = "PHONE_PROBLEM"
}

/** Wire words of [AttendanceRequestView.status]. */
object AttendanceRequestStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REJECTED = "REJECTED"
    /** Feature 34: withdrawn by its author while it was still pending (`POST …/requests/{id}/cancel`). */
    const val CANCELLED = "CANCELLED"
}

/**
 * `POST /staff/attendance/requests`. [date] is `yyyy-MM-dd` in the school's zone. A [AttendanceRequestType.REMOTE_DAY]
 * is for today or later; a [AttendanceRequestType.CORRECTION] is for today or earlier and names [correctionKind] and at
 * least one of [checkIn] / [checkOut] (`HH:mm`, school zone). [attachmentId] is an upload from
 * `POST /staff/attendance/attachments` — a photo or a PDF — and is optional.
 *
 * Feature 34: a [AttendanceRequestType.LEAVE] names [leaveType] and runs from [date] to [toDate] inclusive (one day
 * when [toDate] is left out); a [AttendanceRequestType.PERMISSION] is on [date] from [fromTime] to [toTime] (`HH:mm`).
 * The document rule of a leave type is enforced on [attachmentId].
 */
@Serializable
data class NewAttendanceRequest(
    val type: String,
    val date: String,
    val reason: String,
    val correctionKind: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val attachmentId: String? = null,
    val leaveType: String? = null,
    val toDate: String? = null,
    val fromTime: String? = null,
    val toTime: String? = null,
)

/**
 * One request as both sides see it. [staff] is `<kind>:<id>` as in feature 16's links. [decidedBy] is the decider's
 * name and [decidedAt] epoch milliseconds. [originalCheckIn] / [originalCheckOut] are the times an approved
 * correction replaced (`HH:mm`), null when there was none.
 *
 * Feature 34: a leave carries [leaveType], [toDate] and [days] (the **school days** of her own schedule inside the
 * range); a permission carries [fromTime], [toTime] and [minutes]. [stage] is the step a pending request waits for
 * ([RequestStage]) and null once it is decided; [rejectReason] is set on a rejection and is mandatory for a leave or
 * a permission.
 */
@Serializable
data class AttendanceRequestView(
    val id: String,
    val type: String,
    val status: String,
    val staff: String,
    val staffName: String,
    val date: String,
    val reason: String,
    val correctionKind: String? = null,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val attachment: AttachmentRef? = null,
    val createdAt: Long,
    val decidedBy: String? = null,
    val decidedAt: Long? = null,
    val originalCheckIn: String? = null,
    val originalCheckOut: String? = null,
    val leaveType: String? = null,
    val toDate: String? = null,
    val fromTime: String? = null,
    val toTime: String? = null,
    val days: Int? = null,
    val minutes: Int? = null,
    val stage: String? = null,
    val rejectReason: String? = null,
)

@Serializable
data class AttendanceRequestList(val requests: List<AttendanceRequestView>)

/**
 * `POST /admin/staff/{kind}/{id}/attendance-corrections` — the Admin fixes an employee's day directly. The [reason] is
 * mandatory and the result is recorded as an approved correction decided by her.
 */
@Serializable
data class AdminCorrection(
    val date: String,
    val reason: String,
    val correctionKind: String = CorrectionKind.PHONE_PROBLEM,
    val checkIn: String? = null,
    val checkOut: String? = null,
)

/**
 * Feature 34: the optional body of `POST /attendance/requests/{id}/reject`. A [AttendanceRequestType.LEAVE] or a
 * [AttendanceRequestType.PERMISSION] **must** carry a reason (400 without one), so the Android push's bare **Reject**
 * button — which sends no body at all — opens the app for a leave instead of deciding it from the shade. A remote day
 * and a correction are rejected with or without one, exactly as feature 18 released them.
 */
@Serializable
data class RejectRequest(val reason: String? = null)

/**
 * Feature 34, one row of "رصيدي / My balance" and of the Admin's balance report: the yearly allowance of one
 * [LeaveType] for her category, what approved leave has used of it this year, and what is left.
 * [allowanceDays] and [remainingDays] are null for a type with no balance (unpaid and other, unless the Admin set
 * one), where nothing is ever refused for being over budget. [documentAfterDays] is the document rule: a request
 * longer than that many days needs an attachment, and null means it never does.
 */
@Serializable
data class LeaveTypeBalance(
    val type: String,
    val enabled: Boolean = true,
    val documentAfterDays: Int? = null,
    val allowanceDays: Int? = null,
    val usedDays: Int = 0,
    val remainingDays: Int? = null,
)

/**
 * `GET /staff/attendance/leave/balance`: everything the two forms and the balance screen need — the calendar [year]
 * in the school's zone, her [category], the school's [chain] and its [permissionMaxMinutes], and one row per leave
 * type including the ones the school turned off, so the app can hide them by name rather than by guessing.
 */
@Serializable
data class MyLeaveBalance(
    val year: Int,
    val category: String,
    val chain: String,
    val permissionMaxMinutes: Int,
    val types: List<LeaveTypeBalance> = emptyList(),
)

/**
 * `GET /staff/attendance/leave/preview?leaveType=&from=&to=`: what the app shows under the form before she sends it —
 * the [days] the range will use (the **school days** of her own schedule; [dates] lists them), what that leaves of
 * her balance ([remainingAfter], null for a type with no balance) and whether the document rule asks for a file.
 */
@Serializable
data class LeavePreview(
    val leaveType: String,
    val from: String,
    val to: String,
    val days: Int,
    val dates: List<String> = emptyList(),
    val allowanceDays: Int? = null,
    val usedDays: Int = 0,
    val remainingDays: Int? = null,
    val remainingAfter: Int? = null,
    val documentRequired: Boolean = false,
)

/** The staff app's requests routes. Implemented by the app's staff gateway. */
interface StaffAttendanceRequestsApi {
    suspend fun createAttendanceRequest(request: NewAttendanceRequest): AttendanceRequestView
    suspend fun myAttendanceRequests(): AttendanceRequestList
    /** The requests the caller decides, newest first; [status] narrows them (`PENDING` for the inbox). */
    suspend fun attendanceRequestsToDecide(status: String? = null): AttendanceRequestList
    suspend fun approveAttendanceRequest(id: String): AttendanceRequestView
    suspend fun rejectAttendanceRequest(id: String): AttendanceRequestView

    /**
     * Feature 34: a rejection with its reason. Overloaded rather than a new parameter on
     * [rejectAttendanceRequest] so every gateway and test double released with feature 18 keeps compiling; the
     * default delegates to the bare one, which is a 400 for a leave or a permission.
     */
    suspend fun rejectAttendanceRequest(id: String, reason: String?): AttendanceRequestView = rejectAttendanceRequest(id)

    /** Feature 34: the author withdraws her own request while it is still pending. */
    suspend fun cancelAttendanceRequest(id: String): AttendanceRequestView = error("cancelAttendanceRequest")

    /** Feature 34: "رصيدي / My balance", and the numbers the two request forms show. */
    suspend fun myLeaveBalance(): MyLeaveBalance = error("myLeaveBalance")

    /** Feature 34: the days a leave from [from] to [to] would use, and the balance it would leave. */
    suspend fun leavePreview(leaveType: String, from: String, to: String): LeavePreview = error("leavePreview")
}
