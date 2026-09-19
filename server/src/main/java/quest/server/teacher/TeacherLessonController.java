package quest.server.teacher;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import kotlinx.serialization.KSerializer;
import kotlinx.serialization.builtins.BuiltinSerializersKt;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import quest.api.AdminLesson;
import quest.api.ConfirmedSkill;
import quest.api.JobRef;
import quest.api.LessonFilter;
import quest.api.dto.Curriculum;
import quest.api.dto.LessonStatus;
import quest.api.dto.ParentPanel;
import quest.api.dto.Play;
import quest.api.dto.Stop;
import quest.api.dto.Subject;
import quest.server.admin.AdminLessonService;
import quest.server.analysis.AnalysisService;
import quest.server.auth.Principals;
import quest.server.config.ApiException;
import quest.server.config.Json;
import quest.server.tenancy.TeacherScope;

/**
 * `/teacher/lessons/**`, `/teacher/stops/**` and `/teacher/plays/**` — `docs/teacher-flow.md` §8.
 *
 * <p><strong>Why these exist at all.</strong> Until N2.1 the dashboard's lesson editor called `/admin/lessons/**`
 * with a teacher's token. It worked, because those routes are permitted to TEACHER and scoped by the tenant filter,
 * but it left the teacher's reach decided by `permissions.json` rather than by her assignments: any lesson of her
 * school, written by anyone, was hers to edit. Every handler here resolves the lesson through
 * {@link TeacherScope#requireLesson} first — she wrote it, or she holds the assignment on its class and subject —
 * and only then delegates to {@link AdminLessonService}, which is still the only implementation of the pipeline.
 * `TeacherScopeArchitectureTest` is what keeps that true of routes added later.
 *
 * <p><strong>The flags.</strong> The controller carries no class-level {@link quest.server.flags.FeatureFlag}, and
 * is listed beside {@link TeacherController} in `FeatureFlagCoverageTest` for the same reason: writing lessons is a
 * teacher's job rather than a feature her school can be without, and a flag over it would leave a TEACHER signed in
 * with nothing to do. What <em>is</em> flagged is the part §4 of the schools prompt actually gates — the source she
 * may write from — and that is checked inside {@link TeacherLessonService#create} against
 * `lessons.pdf|slides|images|manual`, because which key applies depends on the body and a route carries one key.
 */
@RestController
@Tag(name = "Teacher lessons", description = "A teacher's own lessons: create, move, copy, publish and the pipeline")
public class TeacherLessonController {
    private final TeacherLessonService teacherLessons; private final AdminLessonService service; private final Json json; private final quest.server.analysis.StopTextService stopText;

    public TeacherLessonController(TeacherLessonService teacherLessons, AdminLessonService service, Json json, quest.server.analysis.StopTextService stopText) {
        this.teacherLessons = teacherLessons; this.service = service; this.json = json; this.stopText = stopText;
    }

    // ---------------------------------------------------------------- her lessons (§8)

    @PostMapping(value = "/teacher/lessons", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String createTeacherLesson(@AuthenticationPrincipal Principals.User caller,
                               @RequestBody @Valid TeacherDto.CreateTeacherLessonRequest body) {
        return lesson(teacherLessons.create(TeacherScope.require(caller), body));
    }

    /**
     * §8's "All lessons" for a teacher — the Admin page's filters, her lessons only. The dashboard called
     * `GET /admin/lessons` with her token until N2.4b, and that route is scoped to the tenant and nothing else, so
     * it showed her every lesson her school holds. There is no `schoolId` parameter on purpose: hers is her token's.
     */
    @GetMapping(value = "/teacher/lessons", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = @ArraySchema(schema = @Schema(implementation = AdminLesson.class))))
    public String listTeacherLessons(@AuthenticationPrincipal Principals.User caller,
                                 @RequestParam(required = false) String curriculum, @RequestParam(required = false) Integer grade,
                                 @RequestParam(required = false) String subject, @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to, @RequestParam(required = false) String classId) {
        var user = TeacherScope.require(caller);
        LessonFilter filter;
        try {
            filter = new LessonFilter(curriculum == null ? null : Curriculum.valueOf(curriculum.toUpperCase()), grade,
                    subject == null ? null : Subject.valueOf(subject.toUpperCase()), date(from), date(to), null, classId);
        } catch (IllegalArgumentException e) { throw ApiException.badRequest("bad filter: " + e.getMessage()); }
        return json.encodeShared(teacherLessons.list(user, filter), BuiltinSerializersKt.ListSerializer(AdminLesson.Companion.serializer()));
    }

    @GetMapping(value = "/teacher/lessons/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String teacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return lesson(service.toAdmin(teacherLessons.require(TeacherScope.require(caller), id), true));
    }

    /** Dragging a card to another day. 409 while it is published. */
    @PatchMapping(value = "/teacher/lessons/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String moveTeacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                             @RequestBody @Valid TeacherDto.MoveLessonRequest body) {
        return lesson(teacherLessons.move(TeacherScope.require(caller), id, body.date()));
    }

    /** Dragging a card onto a sibling row: a full copy, with results of its own. */
    @PostMapping(value = "/teacher/lessons/{id}/copy", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@permit.has('teacher.lesson.copy')")
    @ApiResponse(responseCode = "201", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String copyTeacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                             @RequestBody @Valid TeacherDto.CopyLessonRequest body) {
        return lesson(teacherLessons.copy(TeacherScope.require(caller), id, body.classId()));
    }

    @PostMapping(value = "/teacher/lessons/{id}/publish", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.lesson.publish')")
    public List<TeacherDto.PublishedCopy> publishTeacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                        @RequestBody @Valid TeacherDto.PublishToClassesRequest body) {
        return teacherLessons.publish(TeacherScope.require(caller), id, body.classIds());
    }

    @PostMapping(value = "/teacher/lessons/{id}/unpublish", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('teacher.lesson.publish')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = AdminLesson.class)))
    public String unpublishTeacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return lesson(teacherLessons.unpublish(TeacherScope.require(caller), id));
    }

    @DeleteMapping("/teacher/lessons/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('lesson.delete')")
    public void deleteTeacherLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        teacherLessons.delete(TeacherScope.require(caller), id);
    }

    // ---------------------------------------------------------------- the pipeline, scoped (§8 steps 2–6)

    @PostMapping(value = "/teacher/lessons/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherUploadFiles(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                              @RequestPart("files") List<MultipartFile> files) throws IOException {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        if (files == null || files.isEmpty()) throw ApiException.badRequest("No files.");
        if (files.size() > 10) throw ApiException.badRequest("At most 10 files per lesson.");
        var uploads = new ArrayList<AnalysisService.Upload>();
        for (var f : files) uploads.add(new AnalysisService.Upload(f.getOriginalFilename(), f.getContentType(), f.getBytes()));
        return job(lessonId, service.upload(lessonId, uploads));
    }

    /** "Remove all files" in the editor: the sources go, the lesson stays a draft she can fill again. */
    @DeleteMapping("/teacher/lessons/{id}/files")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('lesson.write')")
    public void teacherDeleteFiles(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        service.deleteFiles(teacherLessons.requireId(TeacherScope.require(caller), id));
    }

    @PostMapping(value = "/teacher/lessons/{id}/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherAnalyze(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return job(lessonId, service.analyze(lessonId));
    }

    @PostMapping(value = "/teacher/lessons/{id}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherRetry(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return job(lessonId, service.retry(lessonId));
    }

    @PostMapping(value = "/teacher/lessons/{id}/steps/{step}/retry", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherRetryStep(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @PathVariable String step) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return job(lessonId, service.retryStep(lessonId, quest.server.analysis.LessonSteps.parse(step)));
    }

    /** PUT rather than the Admin route's POST: confirming the skills is idempotent and the dashboard re-sends it. */
    @PutMapping(value = "/teacher/lessons/{id}/skills", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherConfirmSkills(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return job(lessonId, service.confirmSkills(lessonId, decode(body, BuiltinSerializersKt.ListSerializer(ConfirmedSkill.Companion.serializer()))));
    }

    @PostMapping(value = "/teacher/lessons/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = quest.api.LessonImage.class)))
    public String teacherUploadImage(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                              @RequestPart("file") MultipartFile file) throws IOException {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return json.encodeShared(service.uploadImage(lessonId, file.getOriginalFilename(), file.getContentType(), file.getBytes()),
                quest.api.LessonImage.Companion.serializer());
    }

    @PostMapping(value = "/teacher/lessons/{id}/generate-from-text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = JobRef.class)))
    public String teacherGenerateFromText(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return job(lessonId, service.generateFromText(lessonId, decode(body, quest.api.GenerateFromTextRequest.Companion.serializer()).getText()));
    }

    @PutMapping(value = "/teacher/lessons/{id}/parent-panel", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ParentPanel.class)))
    public String teacherUpdatePanel(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        return json.encodeShared(service.updatePanel(lessonId, decode(body, ParentPanel.Companion.serializer())), ParentPanel.Companion.serializer());
    }

    // ---------------------------------------------------------------- plays and stops
    // A stop and a play are reached through their lesson, so the scope check is the same one: `stopLesson` and
    // `playLesson` resolve the owner first and the write is refused before anything is loaded for editing.

    @PutMapping(value = "/teacher/stops/{stopId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('stop.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Stop.class)))
    public String teacherUpdateStop(@AuthenticationPrincipal Principals.User caller, @PathVariable String stopId, @RequestBody String body) {
        requireStop(caller, stopId);
        return json.encodeShared(service.updateStop(stopId, decode(body, Stop.Companion.serializer())), Stop.Companion.serializer());
    }

    /**
     * CR5: the teacher saves a stop as the English she just edited, and the JSON is the server's problem. The
     * scope check is the same one the raw-JSON `PUT` above makes, and the permission is the same `stop.write` — this
     * is that write, in the words she can read. A text the schema will not take twice over comes back as
     * `422 {"code":"rephrase"}`, with the validator's own lines in `message` for the raw-JSON panel.
     */
    @PostMapping(value = "/teacher/stops/{stopId}/from-text", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('stop.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Stop.class)))
    public String teacherStopFromText(@AuthenticationPrincipal Principals.User caller, @PathVariable String stopId, @RequestBody String body) {
        requireStop(caller, stopId);
        var req = decode(body, quest.api.GenerateFromTextRequest.Companion.serializer());
        return json.encodeShared(stopText.fromText(stopId, req.getText()), Stop.Companion.serializer());
    }

    @PostMapping(value = "/teacher/stops/{stopId}/regenerate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('stop.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Stop.class)))
    public String teacherRegenerateStop(@AuthenticationPrincipal Principals.User caller, @PathVariable String stopId) {
        requireStop(caller, stopId);
        return json.encodeShared(service.regenerateStop(stopId), Stop.Companion.serializer());
    }

    @DeleteMapping("/teacher/stops/{stopId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@permit.has('stop.write')")
    public void teacherDeleteStop(@AuthenticationPrincipal Principals.User caller, @PathVariable String stopId) {
        requireStop(caller, stopId);
        service.deleteStop(stopId);
    }

    /** "Create level" in the editor — a level she writes by hand, on a lesson of hers. */
    @PostMapping(value = "/teacher/lessons/{id}/plays", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('play.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = quest.api.AdminPlay.class)))
    public String teacherCreatePlay(@AuthenticationPrincipal Principals.User caller, @PathVariable String id, @RequestBody String body) {
        String lessonId = teacherLessons.requireId(TeacherScope.require(caller), id);
        var req = decode(body, quest.api.CreatePlayRequest.Companion.serializer());
        return json.encodeShared(service.createPlay(lessonId, req.getLevel(), req.getVariant()), quest.api.AdminPlay.Companion.serializer());
    }

    @PostMapping(value = "/teacher/plays/{playId}/stops", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('stop.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Stop.class)))
    public String teacherAddStop(@AuthenticationPrincipal Principals.User caller, @PathVariable String playId, @RequestBody String body) {
        requirePlay(caller, playId);
        return json.encodeShared(service.addStop(playId, decode(body, Stop.Companion.serializer())), Stop.Companion.serializer());
    }

    @PutMapping(value = "/teacher/plays/{playId}/order", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('play.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Play.class)))
    public String teacherReorder(@AuthenticationPrincipal Principals.User caller, @PathVariable String playId, @RequestBody String body) {
        requirePlay(caller, playId);
        return json.encodeShared(service.reorderStops(playId, decode(body, quest.api.ReorderRequest.Companion.serializer()).getStopIds()), Play.Companion.serializer());
    }

    @PostMapping(value = "/teacher/plays/{playId}/regenerate", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('play.write')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = Play.class)))
    public String teacherRegeneratePlay(@AuthenticationPrincipal Principals.User caller, @PathVariable String playId) {
        requirePlay(caller, playId);
        return json.encodeShared(service.regeneratePlay(playId), Play.Companion.serializer());
    }

    // ---------------------------------------------------------------- plumbing

    private void requireStop(Principals.User caller, String stopId) {
        teacherLessons.require(TeacherScope.require(caller), teacherLessons.lessonOfStop(stopId));
    }

    private void requirePlay(Principals.User caller, String playId) {
        teacherLessons.require(TeacherScope.require(caller), teacherLessons.lessonOfPlay(playId));
    }

    private static kotlinx.datetime.LocalDate date(String value) {
        return value == null || value.isBlank() ? null
                : kotlinx.datetime.LocalDate.Companion.parse(value, kotlinx.datetime.LocalDate.Formats.INSTANCE.getISO());
    }

    private String lesson(AdminLesson l) { return json.encodeShared(l, AdminLesson.Companion.serializer()); }
    private String job(String id, LessonStatus status) { return json.encodeShared(new JobRef(id, status), JobRef.Companion.serializer()); }
    private <T> T decode(String body, KSerializer<T> serializer) {
        try { return json.decodeShared(body, serializer); } catch (Exception e) { throw ApiException.badRequest("Malformed request: " + e.getMessage()); }
    }
}
