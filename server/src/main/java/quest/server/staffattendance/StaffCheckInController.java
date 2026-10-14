package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.api.staff.CheckInMonth;
import quest.api.staff.CheckInRequest;
import quest.api.staff.CheckInResult;
import quest.api.staff.CheckInToday;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 15: the four routes the staff app's Today card and "My record" call (`quest.api.staff.StaffCheckInApi`).
 *
 * <p>Under `/staff` because a WORKER token reaches nothing else, and `attendance.self` is all five staff roles — a
 * parent's Firebase token is refused by `SecurityConfig` before any handler runs. Behind `staffAttendance` here, and
 * behind `staffMobileApp` inside {@link CheckInService}: a school with staff mode off has no `/staff` area to hold an
 * attendance route, and one {@code @FeatureFlag} gates one key.
 *
 * <p>A refusal — the wrong phone, a mocked fix, weak accuracy, outside the geofence — is a <strong>200</strong> whose
 * body says {@code REFUSED} with a reason, so that the attempt row the server just wrote is not rolled back. See
 * {@link CheckInService} for why, and for the order the checks run in.
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_ATTENDANCE)
@Tag(name = "Staff check-in", description = "Geofenced check-in and check-out from the staff app, and the employee's own record")
public class StaffCheckInController {
    private final CheckInService checkIns; private final Json json;

    public StaffCheckInController(CheckInService checkIns, Json json) { this.checkIns = checkIns; this.json = json; }

    @PostMapping(value = "/staff/attendance/check-in", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInResult.class)))
    public String staffCheckIn(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        return json.encodeShared(checkIns.checkIn(caller, decode(body)), CheckInResult.Companion.serializer());
    }

    @PostMapping(value = "/staff/attendance/check-out", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInRequest.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInResult.class)))
    public String staffCheckOut(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        return json.encodeShared(checkIns.checkOut(caller, decode(body)), CheckInResult.Companion.serializer());
    }

    @GetMapping(value = "/staff/attendance/today", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInToday.class)))
    public String staffCheckInToday(@AuthenticationPrincipal Principals.User caller) {
        return json.encodeShared(checkIns.today(caller), CheckInToday.Companion.serializer());
    }

    @GetMapping(value = "/staff/attendance/month", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.self')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CheckInMonth.class)))
    public String staffCheckInMonth(@AuthenticationPrincipal Principals.User caller,
                                    @RequestParam(required = false) String month) {
        return json.encodeShared(checkIns.month(caller, month), CheckInMonth.Companion.serializer());
    }

    private CheckInRequest decode(String body) {
        try { return json.decodeShared(body, CheckInRequest.Companion.serializer()); }
        catch (RuntimeException e) {
            throw ApiException.badRequest("Send {\"latitude\": …, \"longitude\": …, \"accuracyMeters\": …, \"installId\": \"…\"}.");
        }
    }
}
