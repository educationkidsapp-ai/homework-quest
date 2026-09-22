package quest.api.dto

import kotlinx.serialization.Serializable

@Serializable
enum class AttendanceStatus {
    PRESENT,
    ABSENT,
    LATE,
    EXCUSED
}

@Serializable
data class ChildAttendanceRecord(
    val date: String,
    val status: String,
    val notes: String? = null,
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
