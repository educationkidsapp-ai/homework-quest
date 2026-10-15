package quest.server.staffattendance;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.api.staff.CreateRemoteStudyDay;
import quest.api.staff.RemoteStudyDay;
import quest.api.staff.RemoteStudyDayList;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 23: the Admin's remote study days (`quest.api.staff.RemoteStudyDays`). It replaces feature 18's
 * `/admin/attendance/remote-days`, and keeps that feature's permissions — `attendance.settings.read` to see the list
 * and `attendance.settings.write` to declare or cancel a day — because this is the same page of the same screen.
 *
 * <p>The whole controller is behind `remoteStudyDays`: while the flag is off for a school, a school-wide remote day
 * does not exist there, and the routes answer 404 like every flagged area. V46 copied feature 18's rows over, so
 * turning the flag on hands the school back every day it had already declared.
 */
@RestController
@FeatureFlag(FlagKeys.REMOTE_STUDY_DAYS)
@Tag(name = "Remote study days", description = "The school, one academy or chosen grades study from home for a day")
public class RemoteStudyDayController {
    private final RemoteStudyDayService studyDays; private final Json json;

    public RemoteStudyDayController(RemoteStudyDayService studyDays, Json json) {
        this.studyDays = studyDays; this.json = json;
    }

    @GetMapping(value = "/admin/remote-study-days", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RemoteStudyDayList.class)))
    public String remoteStudyDays() {
        return json.encodeShared(studyDays.list(), RemoteStudyDayList.Companion.serializer());
    }

    @PostMapping(value = "/admin/remote-study-days", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('attendance.settings.write')")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = CreateRemoteStudyDay.class)))
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = RemoteStudyDay.class)))
    public String createRemoteStudyDay(@AuthenticationPrincipal Principals.User caller, @RequestBody String body) {
        CreateRemoteStudyDay request;
        try { request = json.decodeShared(body, CreateRemoteStudyDay.Companion.serializer()); }
        catch (RuntimeException e) {
            throw ApiException.badRequest("Send {\"date\": \"yyyy-MM-dd\", \"academy\": null, \"grades\": [], \"reason\": \"…\"}.");
        }
        return json.encodeShared(studyDays.create(caller, request), RemoteStudyDay.Companion.serializer());
    }

    @DeleteMapping(value = "/admin/remote-study-days/{id}")
    @PreAuthorize("@permit.has('attendance.settings.write')")
    @ApiResponse(responseCode = "204")
    public ResponseEntity<Void> deleteRemoteStudyDay(@PathVariable String id) {
        studyDays.delete(id);
        return ResponseEntity.noContent().build();
    }
}
