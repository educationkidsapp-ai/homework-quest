package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.dto.AttachmentRef;
import quest.api.staff.AdminCorrection;
import quest.api.staff.AttendanceRequestList;
import quest.api.staff.AttendanceRequestView;
import quest.api.staff.NewAttendanceRequest;
import quest.api.staff.RemoteDay;
import quest.api.staff.RemoteDayList;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 18: remote days and corrections (`quest.api.staff.AttendanceRequests`).
 *
 * <ul>
 *   <li>`/staff/attendance/requests` and `/staff/attendance/attachments` — the employee's own requests, under
 *       `attendance.self` like feature 15's check-in, so a worker reaches them and a parent never does.</li>
 *   <li>`/attendance/requests/**` — the decider's inbox, approve and reject, under `attendance.requests.decide`
 *       (every role that can be a direct manager, and the Admin). The role only opens the door: which requests a
 *       caller may decide is {@link AttendanceRequestService#mayDecide}. The Android push's Approve and Reject buttons
 *       call these same two routes.</li>
 *   <li>`/admin/attendance/remote-days` and `/admin/staff/{kind}/{id}/attendance-corrections` — the Admin's.</li>
 * </ul>
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_ATTENDANCE)
@Tag(name = "Attendance requests", description = "Remote days and attendance corrections, and their approval")
public class AttendanceRequestsController {
    private final AttendanceRequestService requests; private final RemoteDayService remoteDays; private final Json json;

    public AttendanceRequestsController(AttendanceRequestService requests, RemoteDayService remoteDays, Json json) {
        this.requests = requests; this.remoteDays = remoteDays; this.json = json;
    }

    // ---------------------------------------------------------------- the employee

    @PostMapping(value = "/staff/attendance/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttachmentRef.class)))
    public String staffAttendanceAttachment(@AuthenticationPrincipal Principals.User caller, @RequestPart("file") MultipartFile file) {
        return json.encodeShared(requests.upload(caller, file), AttachmentRef.Companion.serializer());
    }

    @PostMapping(value = "/staff/attendance/requests", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = NewAttendanceRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestView.class)))
    public String createAttendanceRequest(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        var request = decode(body, NewAttendanceRequest.Companion.serializer(), "Send {\"type\": \"REMOTE_DAY\"|\"CORRECTION\", \"date\": \"yyyy-MM-dd\", \"reason\": \"…\"}.");
        return json.encodeShared(requests.create(caller, request), AttendanceRequestView.Companion.serializer());
    }

    @GetMapping(value = "/staff/attendance/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestList.class)))
    public String myAttendanceRequests(@AuthenticationPrincipal Principals.User caller) {
        return json.encodeShared(requests.mine(caller), AttendanceRequestList.Companion.serializer());
    }

    // ---------------------------------------------------------------- the decider

    @GetMapping(value = "/attendance/requests", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.requests.decide')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestList.class)))
    public String attendanceRequestsToDecide(@AuthenticationPrincipal Principals.User caller,
                                             @RequestParam(required = false) String status) {
        return json.encodeShared(requests.toDecide(caller, status), AttendanceRequestList.Companion.serializer());
    }

    @PostMapping(value = "/attendance/requests/{id}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.requests.decide')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestView.class)))
    public String approveAttendanceRequest(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(requests.approve(caller, id), AttendanceRequestView.Companion.serializer());
    }

    @PostMapping(value = "/attendance/requests/{id}/reject", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.requests.decide')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestView.class)))
    public String rejectAttendanceRequest(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(requests.reject(caller, id), AttendanceRequestView.Companion.serializer());
    }

    // ---------------------------------------------------------------- the Admin

    @PostMapping(value = "/admin/staff/{kind}/{id}/attendance-corrections", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.correction.write')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminCorrection.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AttendanceRequestView.class)))
    public String adminAttendanceCorrection(@AuthenticationPrincipal Principals.User caller, @PathVariable String kind,
                                            @PathVariable String id, @RequestBody String body) {
        var correction = decode(body, AdminCorrection.Companion.serializer(), "Send {\"date\": \"yyyy-MM-dd\", \"reason\": \"…\", \"checkIn\": \"HH:mm\"}.");
        return json.encodeShared(requests.adminCorrection(caller, kind, id, correction), AttendanceRequestView.Companion.serializer());
    }

    @GetMapping(value = "/admin/attendance/remote-days", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RemoteDayList.class)))
    public String remoteDays() {
        return json.encodeShared(remoteDays.list(), RemoteDayList.Companion.serializer());
    }

    @PostMapping(value = "/admin/attendance/remote-days", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.write')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RemoteDay.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RemoteDay.class)))
    public String createRemoteDay(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        var day = decode(body, RemoteDay.Companion.serializer(), "Send {\"date\": \"yyyy-MM-dd\", \"academy\": null, \"reason\": \"…\"}.");
        return json.encodeShared(remoteDays.create(caller, day), RemoteDay.Companion.serializer());
    }

    @DeleteMapping(value = "/admin/attendance/remote-days/{id}")
    @PreAuthorize("@permit.has('attendance.settings.write')")
    @ApiResponse(responseCode = "204")
    public org.springframework.http.ResponseEntity<Void> deleteRemoteDay(@PathVariable String id) {
        remoteDays.delete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private <T> T decode(String body, kotlinx.serialization.KSerializer<T> serializer, String hint) {
        try { return json.decodeShared(body, serializer); }
        catch (RuntimeException e) { throw ApiException.badRequest(hint); }
    }
}
