package quest.api.staff

import kotlinx.serialization.Serializable
import quest.api.dto.AttachmentRef

/**
 * Feature 18 `remote-days-corrections` (docs/features/18-remote-days-corrections.md): staff check in from anywhere on
 * an approved remote day (record mode [CheckInMode.REMOTE]), and a missed check-in or check-out is fixed by a request
 * the direct manager approves (mode [CheckInMode.CORRECTION]) — nobody edits a record by hand. Behind `staffAttendance`.
 *
 * **Who decides.** The employee's direct manager as feature 14 resolves it (direct → academy director → the Admins),
 * or any Admin. Nobody else, and never the employee herself.
 *
 * **What stays.** An approved correction writes the claimed time onto the day's record with who approved it and when;
 * the times it replaced are kept on the record ([AttendanceRequestView.originalCheckIn]) and every check-in attempt
 * stays in the log. A rejected request changes nothing.
 */

/** Wire words of [AttendanceRequestView.type]. */
object AttendanceRequestType {
    const val REMOTE_DAY = "REMOTE_DAY"
    const val CORRECTION = "CORRECTION"
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
}

/**
 * `POST /staff/attendance/requests`. [date] is `yyyy-MM-dd` in the school's zone. A [AttendanceRequestType.REMOTE_DAY]
 * is for today or later; a [AttendanceRequestType.CORRECTION] is for today or earlier and names [correctionKind] and at
 * least one of [checkIn] / [checkOut] (`HH:mm`, school zone). [attachmentId] is an upload from
 * `POST /staff/attendance/attachments` — a photo or a PDF — and is optional.
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
)

/**
 * One request as both sides see it. [staff] is `<kind>:<id>` as in feature 16's links. [decidedBy] is the decider's
 * name and [decidedAt] epoch milliseconds. [originalCheckIn] / [originalCheckOut] are the times an approved
 * correction replaced (`HH:mm`), null when there was none.
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
 * A school-wide remote day (`academy == null`) or one academy's (a curriculum such as `british`). `GET`/`POST
 * /admin/attendance/remote-days`, `DELETE /admin/attendance/remote-days/{id}`.
 */
@Serializable
data class RemoteDay(
    val id: String? = null,
    val date: String,
    val academy: String? = null,
    val reason: String,
)

@Serializable
data class RemoteDayList(val days: List<RemoteDay>)

/** The staff app's requests routes. Implemented by the app's staff gateway. */
interface StaffAttendanceRequestsApi {
    suspend fun createAttendanceRequest(request: NewAttendanceRequest): AttendanceRequestView
    suspend fun myAttendanceRequests(): AttendanceRequestList
    /** The requests the caller decides, newest first; [status] narrows them (`PENDING` for the inbox). */
    suspend fun attendanceRequestsToDecide(status: String? = null): AttendanceRequestList
    suspend fun approveAttendanceRequest(id: String): AttendanceRequestView
    suspend fun rejectAttendanceRequest(id: String): AttendanceRequestView
}
