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

    /**
     * Feature 23: {@code remoteStudyDay} is true when a remote study day covers this section — its academy and its
     * grade — on {@code date}, so the teacher's screen says so instead of asking her to mark a roster that is at
     * home. False while the `remoteStudyDays` flag is off for the school.
     */
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
            double attendanceRate,
            boolean remoteStudyDay
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

    /**
     * Feature 23: {@code remoteStudyDay} is true when a remote study day covers the child's class on {@code date}.
     * It is why `GET /children/{id}/attendance/today` can now answer a record on a day nobody marked — status
     * `NOT_MARKED`, the word the class response already uses — rather than the null it used to answer: a parent whose
     * child studies from home is exactly the parent who needs to be told, and the teacher has no roster to take.
     */
    public record ChildAttendanceRecord(
            LocalDate date,
            String status,
            String notes,
            boolean remoteStudyDay
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
