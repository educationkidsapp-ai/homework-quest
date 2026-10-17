package quest.server.hints;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.api.dashboard.ApproveHintsRequest;
import quest.api.dashboard.PracticePlayView;
import quest.api.dashboard.SaveStopHintsRequest;
import quest.api.dashboard.SmartHintsView;
import quest.api.dashboard.StopHintsView;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.teacher.TeacherLessonService;
import quest.server.tenancy.TeacherScope;

/**
 * Feature 30: `/teacher/lessons/{id}/smart-hints**` and `/teacher/lessons/{id}/practice/**` — the teacher reads the
 * hints and the practice sets her lesson was built with, edits a hint, and approves what a child may see.
 *
 * <p>Every handler resolves the lesson through {@link TeacherLessonService#require}, which is
 * {@link TeacherScope#requireLesson}: she wrote it, or she holds the assignment on its class and subject. A
 * colleague's lesson is a 404 before anything is read, exactly as it is on the rest of `/teacher/lessons/{id}/**`.
 *
 * <p>The whole controller carries `smartHints`, so a school that has not switched the feature on answers 404
 * everywhere here — the same body an unknown route gets — and the pipeline writes nothing for it either.
 *
 * <p>An ADMIN reaches these routes too, as she reaches every `/teacher/lessons/{id}/**` route: `permissions.json`
 * grants her `lesson.read` and `lesson.write`, and {@link TeacherScope} narrows a TEACHER and nobody else.
 */
@RestController
@FeatureFlag(FlagKeys.SMART_HINTS)
@Tag(name = "Smart hints", description = "Teacher-approved hints and targeted practice (feature 30)")
public class SmartHintsController {
    private final TeacherLessonService teacherLessons; private final SmartHints smartHints; private final Json json;

    public SmartHintsController(TeacherLessonService teacherLessons, SmartHints smartHints, Json json) {
        this.teacherLessons = teacherLessons; this.smartHints = smartHints; this.json = json;
    }

    @GetMapping(value = "/teacher/lessons/{id}/smart-hints", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SmartHintsView.class)))
    public String smartHints(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        var lesson = teacherLessons.require(TeacherScope.require(caller), id);
        return json.encodeShared(smartHints.view(lesson), SmartHintsView.Companion.serializer());
    }

    /** Her own two hints for one stop. 400 when they break a rule, and the approval is cleared either way. */
    @PutMapping(value = "/teacher/lessons/{id}/smart-hints/{stopId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = StopHintsView.class)))
    public String saveStopHints(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @PathVariable String stopId, @RequestBody String body) {
        var lesson = teacherLessons.require(TeacherScope.require(caller), id);
        var request = decode(body, SaveStopHintsRequest.Companion.serializer());
        return json.encodeShared(smartHints.save(lesson, stopId, request.getHints()), StopHintsView.Companion.serializer());
    }

    /**
     * The named stops, or all of them. The next publish of the lesson carries what she signed here and nothing else;
     * on a lesson that is <em>already</em> published the approval bumps its version, so the body a phone has cached
     * cannot disagree with the body the next phone downloads under the same ETag.
     */
    @PostMapping(value = "/teacher/lessons/{id}/smart-hints/approve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = SmartHintsView.class)))
    public String approveHints(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        var lesson = teacherLessons.require(TeacherScope.require(caller), id);
        smartHints.approve(lesson, decode(body, ApproveHintsRequest.Companion.serializer()), caller.userId());
        return json.encodeShared(smartHints.view(lesson), SmartHintsView.Companion.serializer());
    }

    @PostMapping(value = "/teacher/lessons/{id}/practice/{playId}/approve", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PracticePlayView.class)))
    public String approvePractice(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @PathVariable String playId) {
        var lesson = teacherLessons.require(TeacherScope.require(caller), id);
        return json.encodeShared(smartHints.approvePractice(lesson, playId, caller.userId()), PracticePlayView.Companion.serializer());
    }

    /** She does not want this practice set: it and its play go, and the next build may write another. */
    @PostMapping(value = "/teacher/lessons/{id}/practice/{playId}/reject")
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "204", description = "the practice set is gone")
    public ResponseEntity<Void> rejectPractice(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @PathVariable String playId) {
        var lesson = teacherLessons.require(TeacherScope.require(caller), id);
        smartHints.rejectPractice(lesson, playId);
        return ResponseEntity.noContent().build();
    }

    private <T> T decode(String body, kotlinx.serialization.KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); } catch (Exception e) { throw ApiException.badRequest("Malformed request: " + e.getMessage()); }
    }
}
