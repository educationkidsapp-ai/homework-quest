package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.api.staff.AttendanceRequestView;
import quest.api.staff.LeavePreview;
import quest.api.staff.MyLeaveBalance;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingExports;

/**
 * Feature 34: leave and permission (`quest.api.staff.AttendanceRequests`, `quest.api.dashboard.AttendanceBoard`).
 *
 * <ul>
 *   <li>`/staff/attendance/leave/balance` and `/preview`, and `/staff/attendance/requests/{id}/cancel` — the
 *       employee's own, under `attendance.self` like feature 15's check-in and feature 18's requests, so a worker
 *       reaches them and a parent never does.</li>
 *   <li>`/staff-attendance/leave/balances` and `/leave/requests`, each with its CSV and its Excel file — the
 *       leaders', under `attendance.board`, narrowed to the people the caller leads ({@link BoardScope}) exactly as
 *       feature 17's report is.</li>
 * </ul>
 *
 * <p>The two new <em>types</em> of `POST /staff/attendance/requests` stay on feature 18's route and its controller:
 * one request table, one inbox and one pair of Approve/Reject routes, which is what keeps the released Android build
 * working. The `staffLeave` switch is checked there in the service, and here by this annotation.
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_LEAVE)
@Tag(name = "Staff leave", description = "Leave and permission requests, balances and their two reports")
public class LeaveController {
    private final AttendanceRequestService requests; private final LeaveService leave; private final Json json;

    public LeaveController(AttendanceRequestService requests, LeaveService leave, Json json) {
        this.requests = requests; this.leave = leave; this.json = json;
    }

    // ---------------------------------------------------------------- the employee

    @PostMapping(value = "/staff/attendance/requests/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestView.class)))
    public String cancelAttendanceRequest(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(requests.cancel(caller, id), AttendanceRequestView.Companion.serializer());
    }

    @GetMapping(value = "/staff/attendance/leave/balance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = MyLeaveBalance.class)))
    public String myLeaveBalance(@AuthenticationPrincipal Principals.User caller) {
        return json.encodeShared(leave.myBalance(caller), MyLeaveBalance.Companion.serializer());
    }

    @GetMapping(value = "/staff/attendance/leave/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = LeavePreview.class)))
    public String leavePreview(@AuthenticationPrincipal Principals.User caller, @RequestParam String leaveType,
                               @RequestParam String from, @RequestParam(required = false) String to) {
        return json.encodeShared(leave.preview(caller, leaveType, from, to), LeavePreview.Companion.serializer());
    }

    // ---------------------------------------------------------------- the leaders' two reports

    @GetMapping(value = "/staff-attendance/leave/balances", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.board')")
    public StaffAttendanceDto.LeaveBalanceReport leaveBalances(@AuthenticationPrincipal Principals.User caller,
                                                                @RequestParam(required = false) String year) {
        return leave.balances(caller, year);
    }

    @GetMapping(value = "/staff-attendance/leave/balances.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> leaveBalancesCsv(@AuthenticationPrincipal Principals.User caller,
                                                    @RequestParam(required = false) String year) {
        var report = leave.balances(caller, year);
        return file(LeaveExports.balanceCsv(report), "text/csv; charset=UTF-8", LeaveExports.balanceFilename(report, "csv"));
    }

    @GetMapping(value = "/staff-attendance/leave/balances.xlsx", produces = GradingExports.XLSX)
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> leaveBalancesXlsx(@AuthenticationPrincipal Principals.User caller,
                                                     @RequestParam(required = false) String year) {
        var report = leave.balances(caller, year);
        return file(LeaveExports.balanceXlsx(report), GradingExports.XLSX, LeaveExports.balanceFilename(report, "xlsx"));
    }

    @GetMapping(value = "/staff-attendance/leave/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.board')")
    public StaffAttendanceDto.LeaveRequestReport leaveRequests(@AuthenticationPrincipal Principals.User caller,
                                                                @RequestParam(required = false) String month) {
        return leave.monthlyRequests(caller, month);
    }

    @GetMapping(value = "/staff-attendance/leave/requests.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> leaveRequestsCsv(@AuthenticationPrincipal Principals.User caller,
                                                    @RequestParam(required = false) String month) {
        var report = leave.monthlyRequests(caller, month);
        return file(LeaveExports.requestCsv(report), "text/csv; charset=UTF-8", LeaveExports.requestFilename(report, "csv"));
    }

    @GetMapping(value = "/staff-attendance/leave/requests.xlsx", produces = GradingExports.XLSX)
    @PreAuthorize("@permit.has('attendance.board')")
    public ResponseEntity<byte[]> leaveRequestsXlsx(@AuthenticationPrincipal Principals.User caller,
                                                     @RequestParam(required = false) String month) {
        var report = leave.monthlyRequests(caller, month);
        return file(LeaveExports.requestXlsx(report), GradingExports.XLSX, LeaveExports.requestFilename(report, "xlsx"));
    }

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
