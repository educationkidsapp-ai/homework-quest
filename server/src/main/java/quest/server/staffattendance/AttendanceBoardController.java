package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingExports;

/**
 * Feature 17: the leaders' Staff attendance page — today's board, one employee's day, and the monthly report with its
 * CSV and Excel exports.
 *
 * <p>`attendance.board` is ADMIN, MANAGERIAL, COORDINATOR and TEACHER: anyone who may be named a direct manager.
 * What each of them sees is {@link BoardScope}'s answer, not the permission's. Not WORKER, and never a parent. Behind
 * `staffAttendance`: a school that does not use staff attendance never sees the routes (404).
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_ATTENDANCE)
@Tag(name = "Staff attendance board", description = "Today's staff attendance, one employee's day, and the monthly report")
public class AttendanceBoardController {
    private final AttendanceBoardService board;

    public AttendanceBoardController(AttendanceBoardService board) { this.board = board; }

    @GetMapping(value = "/staff-attendance/board", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.board')")
    public StaffAttendanceDto.AttendanceBoard attendanceBoard(@AuthenticationPrincipal Principals.User caller) {
        return board.board(caller);
    }

    @GetMapping(value = "/staff-attendance/employees/{kind}/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.board')")
    public StaffAttendanceDto.EmployeeDay attendanceEmployeeDay(@AuthenticationPrincipal Principals.User caller,
                                                                @PathVariable String kind, @PathVariable String id,
                                                                @RequestParam(required = false) String day) {
        return board.employeeDay(caller, kind, id, day);
    }

    @GetMapping(value = "/staff-attendance/report", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.board')")
    public StaffAttendanceDto.AttendanceReport attendanceReport(@AuthenticationPrincipal Principals.User caller,
                                                                @RequestParam(required = false) String month) {
        return board.report(caller, month);
    }

    @GetMapping(value = "/staff-attendance/report.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> attendanceReportCsv(@AuthenticationPrincipal Principals.User caller,
                                                      @RequestParam(required = false) String month) {
        var report = board.report(caller, month);
        return file(AttendanceReportExport.csv(report), "text/csv; charset=UTF-8", AttendanceReportExport.filename(report, "csv"));
    }

    @GetMapping(value = "/staff-attendance/report.xlsx", produces = GradingExports.XLSX)
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> attendanceReportXlsx(@AuthenticationPrincipal Principals.User caller,
                                                       @RequestParam(required = false) String month) {
        var report = board.report(caller, month);
        return file(AttendanceReportExport.xlsx(report), GradingExports.XLSX, AttendanceReportExport.filename(report, "xlsx"));
    }

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
