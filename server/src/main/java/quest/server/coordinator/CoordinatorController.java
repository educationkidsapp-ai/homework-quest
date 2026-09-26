package quest.server.coordinator;

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
import quest.server.tenancy.CoordinatorScope;

/**
 * `/coordinator/**` — the coordinator's area (`docs/plan.md` phase R, DR1/DR2).
 *
 * <p><strong>Every route here reads.</strong> DR2: "the coordinator is read-only on teaching data … writes are only
 * communication", and those arrive with R4 as chat, complaint status and announcements. There is no POST, PUT, PATCH
 * or DELETE in this package, so a coordinator asking for one of the teacher's writes is refused twice — by
 * `permissions.json`, which grants her no `*.write` key at all, and by `SecurityConfig`, which does not let the role
 * knock on `/teacher/**` or `/admin/**` in the first place. `PermissionsTest` checks the first half route by route.
 *
 * <p><strong>Scope.</strong> The namespace names no school and no subject: hers come from her token and her
 * `staff_scopes` rows, through {@link CoordinatorScope}. `classId` is the only parameter that names a row and it is
 * resolved through {@link CoordinatorScope#requireSection} — 404 for another school's, 403 for another subject's.
 * `CoordinatorScopeArchitectureTest` fails the build when a handler added later skips that.
 *
 * <p><strong>Carries no {@link quest.server.flags.FeatureFlag}</strong>, and is named in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE` beside {@code TeacherController} and {@code TeacherLessonController} for
 * exactly their reason: this is not one feature of a coordinator's dashboard, it <em>is</em> the dashboard of a role,
 * and a flag over it leaves a COORDINATOR signed in with nowhere to go and nothing to read. What the area shows about
 * a flagged feature stays behind that feature's own flag, where the feature lives — the gradebook, exam and
 * attendance reads are R3 and hang off `gradebook`, `exams` and their own routes, and the communication half is R4
 * behind `chat`, `complaints` and `announcements`.
 *
 * <p>The two lesson routes answer the shared kotlinx encoding of {@link AdminLesson}, like every other route that
 * carries a lesson: a lesson holds `Stop`s and only that codec can write one. The `@ApiResponse` is what puts the
 * real schema in `server/openapi.json` rather than `type: string`.
 */
@RestController
@Tag(name = "Coordinator", description = "A subject coordinator's read-only view of the school she supervises")
public class CoordinatorController {
    private final CoordinatorService coordinators; private final CoordinatorReadsService reads; private final Json json;

    public CoordinatorController(CoordinatorService coordinators, CoordinatorReadsService reads, Json json) {
        this.coordinators = coordinators; this.reads = reads; this.json = json;
    }

    /** Her scope and its three numbers — what the Home screen leads with, in one request. */
    @GetMapping(value = "/coordinator/me", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.read')")
    public CoordinatorDto.CoordinatorMe coordinatorMe(@AuthenticationPrincipal Principals.User caller) {
        return coordinators.me(CoordinatorScope.require(caller));
    }

    /** The teachers she supervises: every one holding an assignment in scope, with the sections that put her there. */
    @GetMapping(value = "/coordinator/teachers", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.read')")
    public List<CoordinatorDto.CoordinatorTeacher> coordinatorTeachers(@AuthenticationPrincipal Principals.User caller) {
        return coordinators.teachers(CoordinatorScope.require(caller));
    }

    /** A card per (section, subject) in scope — grade, curriculum, teacher, class size, today's lesson. */
    @GetMapping(value = "/coordinator/classes", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.read')")
    public List<CoordinatorDto.CoordinatorClass> coordinatorClasses(@AuthenticationPrincipal Principals.User caller) {
        return coordinators.classes(CoordinatorScope.require(caller));
    }

    /** Every class in scope, day by day. Both bounds absent is this school week; the window is capped at 62 days. */
    @GetMapping(value = "/coordinator/calendar", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.read')")
    public CoordinatorDto.CoordinatorCalendar coordinatorCalendar(@AuthenticationPrincipal Principals.User caller,
                                                                  @RequestParam(required = false) String from,
                                                                  @RequestParam(required = false) String to) {
        return coordinators.calendar(CoordinatorScope.require(caller), from, to);
    }

    /**
     * `GET /coordinator/classes/{id}/attendance?from&to` — the teacher's own per-day, per-child register for a section
     * she supervises, one body per day of the window. Both bounds absent is the week ending today, and the window is
     * capped like the calendar's.
     *
     * <p>It sits on this class rather than on {@link CoordinatorReadsController} because it names no feature flag, for
     * the reason {@code AttendanceController} names none: taking register is not an optional feature and
     * {@link quest.server.flags.FlagKeys} has no key for it. R3's flagged reads are next door, where every handler
     * does name one.
     */
    @GetMapping(value = "/coordinator/classes/{id}/attendance", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.attendance.read')")
    public List<quest.server.attendance.AttendanceDto.ClassAttendanceResponse> coordinatorAttendance(
            @AuthenticationPrincipal Principals.User caller, @PathVariable String id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return reads.attendance(CoordinatorScope.require(caller), id, from, to);
    }

    /** The teacher's lesson rows, reduced to her subject. `status` is `draft`, `ready` or `published`. */
    @GetMapping(value = "/coordinator/lessons", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = AdminLesson.class))))
    public String coordinatorLessons(@AuthenticationPrincipal Principals.User caller,
                                     @RequestParam(required = false) String classId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to) {
        var rows = coordinators.lessons(CoordinatorScope.require(caller), classId, status, from, to);
        return json.encodeShared(rows, BuiltinSerializersKt.ListSerializer(AdminLesson.Companion.serializer()));
    }

    /** The same lesson body the teacher reads, and no route under `/coordinator` that writes it. */
    @GetMapping(value = "/coordinator/lessons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String coordinatorLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(coordinators.lesson(CoordinatorScope.require(caller), id), AdminLesson.Companion.serializer());
    }

    /**
     * E1's poll, for her read-only lesson page: the same `LessonStatusView` the teacher's and the Admin's `/status`
     * routes answer. It is here rather than beside R3's other reads because it carries no feature flag — the subject
     * of the route is the lesson itself, which is this role's own area, and `SecurityConfig` keeps her out of
     * `/teacher/**`, so without this route her page would have nothing to tick against.
     */
    @GetMapping(value = "/coordinator/lessons/{id}/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = quest.api.LessonStatusView.class)))
    public String coordinatorLessonStatus(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return json.encodeShared(coordinators.lessonStatus(CoordinatorScope.require(caller), id),
                quest.api.LessonStatusView.Companion.serializer());
    }
}
