package quest.api.dto

import kotlinx.serialization.Serializable

@Serializable
enum class AttendanceStatus {
    PRESENT,
    ABSENT,
    LATE,
    EXCUSED
}

/**
 * One day of a child's attendance. Feature 23: [remoteStudyDay] is true when a remote study day covered the child's
 * class that day — her academy and her grade — and false while the school's `remoteStudyDays` flag is off.
 *
 * On a remote study day `GET /children/{id}/attendance/today` answers a record even though nobody marked a roster
 * that is at home: [status] is then `NOT_MARKED`, the word the teacher's class response already uses, and the app
 * says "Remote study day" rather than showing a status chip.
 */
@Serializable
data class ChildAttendanceRecord(
    val date: String,
    val status: String,
    val notes: String? = null,
    val remoteStudyDay: Boolean = false,
)

@Serializable
data class ChildAttendanceSummary(
    val totalDays: Int = 0,
    val presentDays: Int = 0,
    val absentDays: Int = 0,
    val lateDays: Int = 0,
    val excusedDays: Int = 0,
    val attendanceRate: Double = 100.0,
)

@Serializable
data class ChildAttendanceResponse(
    val records: List<ChildAttendanceRecord> = emptyList(),
    val summary: ChildAttendanceSummary = ChildAttendanceSummary(),
)
