package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.api.staff.ApprovedDevice;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 14: the Admin's Attendance settings page (`/admin/attendance-settings`) and every employee's category and
 * direct manager (`/admin/staff/attendance-profiles`).
 *
 * <p><strong>`attendance.settings.read` is ADMIN and MANAGERIAL, `attendance.settings.write` ADMIN only.</strong> A
 * manager sees the rules her people are held to; only the Admin changes them. Behind `staffAttendance`: a school that
 * does not use staff attendance never sees the routes (404).
 */
@RestController
@FeatureFlag(FlagKeys.STAFF_ATTENDANCE)
@Tag(name = "Staff attendance settings", description = "School pin, staff hours, holidays, rules and direct managers")
public class StaffAttendanceSettingsController {
    private final AttendanceSettingsService settings; private final StaffAttendanceProfileService profiles;
    private final AttendanceDeviceService devices; private final Json json;

    public StaffAttendanceSettingsController(AttendanceSettingsService settings, StaffAttendanceProfileService profiles,
                                             AttendanceDeviceService devices, Json json) {
        this.settings = settings; this.profiles = profiles; this.devices = devices; this.json = json;
    }

    @GetMapping(value = "/admin/attendance-settings", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    public StaffAttendanceDto.AttendanceSettings attendanceSettings() { return settings.read(); }

    @PutMapping(value = "/admin/attendance-settings", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.write')")
    public StaffAttendanceDto.AttendanceSettings updateAttendanceSettings(@AuthenticationPrincipal Principals.User caller,
                                                                          @RequestBody StaffAttendanceDto.UpdateAttendanceSettingsRequest body) {
        return settings.update(TeacherScope.require(caller), body);
    }

    @GetMapping(value = "/admin/staff/attendance-profiles", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    public List<StaffAttendanceDto.StaffAttendanceProfile> staffAttendanceProfiles() { return profiles.list(); }

    @PutMapping(value = "/admin/staff/attendance-profiles", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.write')")
    public List<StaffAttendanceDto.StaffAttendanceProfile> updateStaffAttendanceProfiles(@AuthenticationPrincipal Principals.User caller,
                                                                                         @RequestBody @Valid StaffAttendanceDto.UpdateStaffAttendanceProfilesRequest body) {
        return profiles.update(TeacherScope.require(caller), body);
    }

    // ---------------------------------------------------------------- feature 15: the employee's approved phone

    /**
     * The phone bound to one employee, on her page in the Admin's and the manager's staff screens. An empty
     * {@code installId} means she has not checked in yet, or that her device was just reset.
     */
    @GetMapping(value = "/admin/staff/{kind}/{id}/attendance-device", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApprovedDevice.class)))
    public String approvedDevice(@PathVariable String kind, @PathVariable String id) {
        return json.encodeShared(devices.read(kind, id), ApprovedDevice.Companion.serializer());
    }

    /**
     * Reset: her next successful check-in binds whatever phone she uses. ADMIN only — a manager sees which phone is
     * approved, and approving a new one is the Admin's act, audited with the employee and no location data at all.
     */
    @DeleteMapping(value = "/admin/staff/{kind}/{id}/attendance-device", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.device.reset')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApprovedDevice.class)))
    public String resetApprovedDevice(@AuthenticationPrincipal Principals.User caller,
                                      @PathVariable String kind, @PathVariable String id) {
        return json.encodeShared(devices.reset(TeacherScope.require(caller), kind, id), ApprovedDevice.Companion.serializer());
    }
}
