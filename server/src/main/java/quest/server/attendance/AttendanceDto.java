package quest.server.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class AttendanceDto {
    private AttendanceDto() {}

    public record ClassAttendanceItem(
            String childId,
            String childName,
            String avatarColor,
            String photoUrl,
            String status,
            String notes,
            Instant updatedAt
    ) {}

    public record ClassAttendanceResponse(
            String classId,
            String className,
            LocalDate date,
            List<ClassAttendanceItem> students,
            int totalCount,
            int presentCount,
            int absentCount,
            int lateCount,
            int excusedCount,
            double attendanceRate
    ) {}

    public record SaveAttendanceRequest(
            LocalDate date,
            List<SaveAttendanceItem> items
    ) {}

    public record SaveAttendanceItem(
            String childId,
            String status,
            String notes
    ) {}

    public record ChildAttendanceRecord(
            LocalDate date,
            String status,
            String notes
    ) {}

    public record ChildAttendanceSummary(
            int totalDays,
            int presentDays,
            int absentDays,
            int lateDays,
            int excusedDays,
            double attendanceRate
    ) {}

    public record ChildAttendanceResponse(
            List<ChildAttendanceRecord> records,
            ChildAttendanceSummary summary
    ) {}
}
