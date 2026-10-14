package quest.api.staff

import kotlinx.serialization.Serializable

/**
 * Feature 15 `geofence-check-in` (docs/features/15-geofence-check-in.md): an employee records arrival and departure
 * from the phone, inside the school's geofence, confirmed by the phone's face, fingerprint or screen lock. Behind the
 * `staffAttendance` flag (and `staffMobileApp`, since the routes live under `/staff`); `attendance.self` is every
 * staff role, never a parent.
 *
 * **The server decides.** The time of a record is the server's clock in the school's time zone; [CheckInRequest.phoneTime]
 * is kept on the attempt for audit only. The distance is computed on the server and the coordinates are then
 * discarded: no table, log line or audit entry ever holds a latitude or a longitude.
 *
 * Every call is logged as an attempt, accepted or not, so a refusal is a `200` with [CheckInResult.result] =
 * [CheckInOutcome.REFUSED] and a [CheckInResult.reason], not an error status.
 */

/** Wire words of [CheckInResult.result]. */
object CheckInOutcome {
    /** Recorded now; [CheckInResult.serverTime] is the recorded time. */
    const val RECORDED = "RECORDED"
    /** A second check-in on the same day: nothing changes, [CheckInResult.serverTime] is the first one. */
    const val ALREADY = "ALREADY"
    /** Nothing recorded; see [CheckInResult.reason]. */
    const val REFUSED = "REFUSED"
}

/** Wire words of [CheckInResult.reason], in the order the server checks them. */
object CheckInRefusal {
    /** This phone is not the one bound to the employee. The Admin resets the device to approve a new one. */
    const val DEVICE_NOT_APPROVED = "DEVICE_NOT_APPROVED"
    /** Android reported a mock location. Logged as suspicious. */
    const val MOCK_LOCATION = "MOCK_LOCATION"
    /** The fix's accuracy is worse than [CheckInRules.MAX_ACCURACY_METERS]. */
    const val WEAK_ACCURACY = "WEAK_ACCURACY"
    /** Further than the school's radius; [CheckInResult.distanceMeters] says how far. */
    const val OUTSIDE = "OUTSIDE"
    /** The school has not placed its pin yet (feature 14's settings page). */
    const val NO_GEOFENCE = "NO_GEOFENCE"
    /** A check-out with no check-in today. */
    const val NOT_CHECKED_IN = "NOT_CHECKED_IN"
}

object CheckInRules {
    const val MAX_ACCURACY_METERS = 100.0
}

/** Wire words of `mode`. Only [ONSITE] exists in v1; the rest arrive with features 18 and later. */
object CheckInMode {
    const val ONSITE = "onsite"
    const val REMOTE = "remote"
    const val DEVICE = "device"
    const val CORRECTION = "correction"
}

/**
 * `POST /staff/attendance/check-in` and `/check-out`. [installId] is the app install's own random id, the device the
 * first successful check-in binds. [phoneTime] is the phone's ISO-8601 instant, for audit only.
 */
@Serializable
data class CheckInRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val mocked: Boolean = false,
    val installId: String,
    val phoneTime: String? = null,
)

/**
 * The answer to a check-in or a check-out. [serverTime] is `HH:mm` in the school's time zone; [lateMinutes] is set on a
 * recorded check-in only (0 = on time, within the grace period); [distanceMeters] is rounded to the metre.
 */
@Serializable
data class CheckInResult(
    val result: String,
    val reason: String? = null,
    val serverTime: String? = null,
    val lateMinutes: Int? = null,
    val distanceMeters: Int? = null,
)

/** `GET /staff/attendance/today` — what the Today card shows. Times are `HH:mm`, school time zone. */
@Serializable
data class CheckInToday(
    val date: String,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val lateMinutes: Int? = null,
    /** False on a day off or a holiday; the button still works, nothing is late. */
    val workDay: Boolean = true,
)

/** One line of "My record". [status] is `ON_TIME`, `LATE`, `ABSENT`, `DAY_OFF`, `HOLIDAY` or `UPCOMING`. */
@Serializable
data class CheckInDay(
    val date: String,
    val checkIn: String? = null,
    val checkOut: String? = null,
    val status: String,
    val lateMinutes: Int? = null,
    val mode: String? = null,
)

/** `GET /staff/attendance/month?month=YYYY-MM` — absent `month` is the current one. Days in order. */
@Serializable
data class CheckInMonth(val month: String, val days: List<CheckInDay>)

/**
 * `GET /admin/staff/{kind}/{id}/attendance-device` — the approved device, or `installId == null` when none is bound.
 * `DELETE` on the same path resets it; the next successful check-in binds the new phone.
 */
@Serializable
data class ApprovedDevice(
    val installId: String? = null,
    val firstSeen: String? = null,
)

/** The four staff-app routes. Implemented by the app's staff gateway; served by `StaffCheckInController`. */
interface StaffCheckInApi {
    suspend fun checkIn(request: CheckInRequest): CheckInResult
    suspend fun checkOut(request: CheckInRequest): CheckInResult
    suspend fun checkInToday(): CheckInToday
    suspend fun checkInMonth(month: String? = null): CheckInMonth
}
