package quest.feature.parent.presentation

import quest.api.dto.ChildAttendanceRecord
import quest.ui.design.DashboardPillVariant

/**
 * What the parent's attendance row says about one day, for her Today card and for a day she picks in the calendar —
 * one mapping, so both read the same.
 *
 * Feature 23: on a **remote study day** the class studied from home, so the server answers a record nobody marked
 * (`status: "NOT_MARKED"`, `remoteStudyDay: true`) and the row says "Remote study day" with no status chip at all.
 * A status this app does not know — `NOT_MARKED` among them — is never shown raw and never as an error: it reads
 * "Not recorded yet", calmly, exactly as a day with no record does.
 */
data class AttendanceDisplay(
    /** The line in the row's own words; never a raw wire status. */
    val headline: String,
    /** The status chip, or null when there is no status to show. */
    val chip: String? = null,
    val variant: DashboardPillVariant = DashboardPillVariant.NEUTRAL,
    /** True when the line is an absence of news rather than news: it is drawn in the soft ink. */
    val muted: Boolean = false,
)

/** Maps [record] (null when the day holds nothing) to what the row shows, in the parent's language. */
fun attendanceDisplay(record: ChildAttendanceRecord?, s: Strings): AttendanceDisplay = when {
    record == null -> AttendanceDisplay(s.noAttendanceRecorded, muted = true)
    record.remoteStudyDay -> AttendanceDisplay(s.remoteStudyDay)
    else -> when (record.status.uppercase()) {
        "PRESENT" -> AttendanceDisplay("✓ ${s.present}", s.present, DashboardPillVariant.SUCCESS)
        "LATE" -> AttendanceDisplay("⏰ ${s.late}", s.late, DashboardPillVariant.WARNING)
        "ABSENT" -> AttendanceDisplay("✕ ${s.absent}", s.absent, DashboardPillVariant.ERROR)
        "EXCUSED" -> AttendanceDisplay("ℹ ${s.excused}", s.excused, DashboardPillVariant.INFO)
        else -> AttendanceDisplay(s.noAttendanceRecorded, muted = true)
    }
}
