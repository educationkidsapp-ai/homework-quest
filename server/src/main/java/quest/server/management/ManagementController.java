package quest.server.management;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.api.AdminLesson;
import quest.server.auth.Principals;
import quest.server.config.Json;
import quest.server.coordinator.CoordinatorDto;
import quest.server.tenancy.ManagerScope;

/**
 * `/management/**` — a department manager's area (`docs/plan.md` phase R, DR5).
 *
 * <p><strong>Every route here reads.</strong> DR5 gives the manager the department's people and numbers; her writes are
 * the weekly plan, announcements and chat (RM2) and staff attendance (RM5), and each arrives in its own package with
 * its own flag. There is no POST, PUT, PATCH or DELETE under `/management` in RM1 at all, and
 * `ManagerScopeArchitectureTest` asserts the verb rather than leaving it to review.
 *
 * <p><strong>Scope.</strong> The namespace names no school, no grade and no track: hers come from her token and her
 * `staff_scopes` rows, through {@link ManagerScope}. `classId`, `lessonId` and `childId` are the only parameters that
 * name a row and each is resolved — 404 for another school's, 403 for the other department's.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code CoordinatorController} for exactly its reason: this is not one
 * feature of a manager's dashboard, it <em>is</em> the dashboard of a role, and a flag over it leaves a MANAGERIAL user
 * signed in with nowhere to go. What the area shows about a flagged feature stays behind that feature's own flag, where
 * the feature lives — the gradebook and exam reads are next door on {@link ManagementReadsController} and carry
 * `gradebook` and `exams`, and the register carries none because `AttendanceController` carries none.
 *
 * <p>The three lesson routes answer the shared kotlinx encoding, like every other route that carries a lesson: a lesson
 * holds `Stop`s and only that codec can write one. The `@ApiResponse` is what puts the real schema in
 * `server/openapi.json` rather than `type: string`.
 */
@RestController
@Tag(name = "Management", description = "A department manager's read-only view of every grade she manages")
public class ManagementController {
    private final ManagementService management; private final ManagementStatsService stats; private final Json json;

    public ManagementController(ManagementService management, ManagementStatsService stats, Json json) {
        this.management = management; this.stats = stats; this.json = json;
    }

    /**
     * MG1: her department's School usage — children, active families, plays a day, lessons published a week and how
     * steadily each of <em>her</em> teachers publishes. The whole-school `GET /school/usage` answers the same shape;
     * this one is scoped, so a manager is never shown the other department's numbers.
     */
    @GetMapping(value = "/management/usage", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public quest.server.dashboard.SchoolDataDto.SchoolUsage managementUsage(@AuthenticationPrincipal Principals.User caller,
                                                                            @RequestParam(required = false) String from,
                                                                            @RequestParam(required = false) String to) {
        return management.usage(ManagerScope.require(caller), from, to);
    }

    /** Her departments and their five numbers — what Home leads with, in one request. */
    @GetMapping(value = "/management/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public ManagementDto.ManagerMe managementMe(@AuthenticationPrincipal Principals.User caller) {
        return management.me(ManagerScope.require(caller));
    }

    /** The coordinators she manages, with their subjects, their tracks and how much of the department they cover. */
    @GetMapping(value = "/management/coordinators", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public List<ManagementDto.ManagerCoordinator> managementCoordinators(@AuthenticationPrincipal Principals.User caller) {
        return management.coordinators(ManagerScope.require(caller));
    }

    /** Every teacher of the department, with her assignments — the coordinator's own rows, one scope wider. */
    @GetMapping(value = "/management/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public List<CoordinatorDto.CoordinatorTeacher> managementTeachers(@AuthenticationPrincipal Principals.User caller) {
        return management.teachers(ManagerScope.require(caller));
    }

    /** The department's grades, each with its section cards: grade, curriculum, teacher, size, today's lesson. */
    @GetMapping(value = "/management/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public List<ManagementDto.GradeGroup> managementClasses(@AuthenticationPrincipal Principals.User caller) {
        return management.classes(ManagerScope.require(caller));
    }

    /** Every class of the department, day by day. Both bounds absent is this school week; the window is capped at 62 days. */
    @GetMapping(value = "/management/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public CoordinatorDto.CoordinatorCalendar managementCalendar(@AuthenticationPrincipal Principals.User caller,
                                                                 @RequestParam(required = false) String from,
                                                                 @RequestParam(required = false) String to) {
        return management.calendar(ManagerScope.require(caller), from, to);
    }

    /**
     * DR5's statistics: a row per grade and the department's own total — children, sections, attendance rate, lessons
     * published and played, the exam average and pass rate, and the teachers who published nothing in the window.
     * Both bounds absent is the month ending today; the window is capped at a term.
     */
    @GetMapping(value = "/management/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.read')")
    public ManagementDto.ManagementStats managementStats(@AuthenticationPrincipal Principals.User caller,
                                                         @RequestParam(required = false) String from,
                                                         @RequestParam(required = false) String to) {
        return stats.stats(ManagerScope.require(caller), from, to);
    }

    /**
     * `GET /management/classes/{id}/attendance?from&to` — the teacher's own per-day register for a section of her
     * department, one body per day of the window. It sits here rather than on {@link ManagementReadsController} because
     * it names no feature flag, for the reason `AttendanceController` names none: taking register is not an optional
     * feature and {@link quest.server.flags.FlagKeys} has no key for it.
     */
    @GetMapping(value = "/management/classes/{id}/attendance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.attendance.read')")
    public List<quest.server.attendance.AttendanceDto.ClassAttendanceResponse> managementAttendance(
            @AuthenticationPrincipal Principals.User caller, @PathVariable String id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return management.attendance(ManagerScope.require(caller), id, from, to);
    }

    /** The teacher's lesson rows, reduced to the department. `status` is `draft`, `ready` or `published`. */
    @GetMapping(value = "/management/lessons", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = AdminLesson.class))))
    public String managementLessons(@AuthenticationPrincipal Principals.User caller,
                                    @RequestParam(required = false) String classId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        var rows = management.lessons(ManagerScope.require(caller), classId, status, from, to);
        return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(AdminLesson.Companion.serializer()));
    }

    /** The same lesson body the teacher reads, and no route under `/management` that writes it. */
    @GetMapping(value = "/management/lessons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String managementLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(management.lesson(ManagerScope.require(caller), id), AdminLesson.Companion.serializer());
    }

    /** E1's poll for her read-only lesson page: the same `LessonStatusView` the teacher's `/status` route answers. */
    @GetMapping(value = "/management/lessons/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = quest.api.LessonStatusView.class)))
    public String managementLessonStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(management.lessonStatus(ManagerScope.require(caller), id),
                quest.api.LessonStatusView.Companion.serializer());
    }
}
