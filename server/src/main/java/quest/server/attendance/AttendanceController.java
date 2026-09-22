package quest.server.attendance;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;

@RestController
@Tag(name = "Attendance", description = "Teacher class attendance taking and parent attendance reflection")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @GetMapping(value = "/teacher/classes/{classId}/attendance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.read')")
    public AttendanceDto.ClassAttendanceResponse getClassAttendance(
            @AuthenticationPrincipal Principals.User caller,
            @PathVariable String classId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.getClassAttendance(caller, classId, date);
    }

    @PostMapping(value = "/teacher/classes/{classId}/attendance", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.write')")
    public AttendanceDto.ClassAttendanceResponse saveClassAttendance(
            @AuthenticationPrincipal Principals.User caller,
            @PathVariable String classId,
            @RequestBody AttendanceDto.SaveAttendanceRequest request) {
        return attendanceService.saveClassAttendance(caller, classId, request);
    }

    @GetMapping(value = "/children/{id}/attendance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.attendance.read')")
    public AttendanceDto.ChildAttendanceResponse getChildAttendance(
            @AuthenticationPrincipal Principals.Parent parent,
            @PathVariable String id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendanceService.getChildAttendance(parent, id, from, to);
    }

    @GetMapping(value = "/children/{id}/attendance/today", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('child.attendance.read')")
    public AttendanceDto.ChildAttendanceRecord getTodayAttendance(
            @AuthenticationPrincipal Principals.Parent parent,
            @PathVariable String id) {
        return attendanceService.getTodayAttendance(parent, id);
    }
}
