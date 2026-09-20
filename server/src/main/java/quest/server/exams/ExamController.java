package quest.server.exams;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingDto;
import quest.server.platform.PlatformSettingsService;
import quest.server.tenancy.TeacherScope;

/**
 * `docs/teacher-flow.md` step 10 — exams: the settings sheet, publish, release, the one re-opening per child, the
 * results page and its three exports.
 *
 * <p><strong>The flag.</strong> `exams` on the class, so a school that has not switched the feature on gets 404 for
 * every route here — including the exports — rather than an empty page. That is the same silence a route that never
 * existed gives, which is §4's rule.
 *
 * <p><strong>Scope, and the Admin.</strong> Every handler resolves the exam or the class it names through
 * {@link TeacherScope} inside {@link ExamService} — another school's is a 404, another teacher's is a 403. There is
 * no separate `/admin/**` alias, for the reason {@link quest.server.grading.GradingController} gives: `TeacherScope`
 * already lets an ADMIN scoped with `X-School-Id` reach any class of the school she picked, and `permissions.json`
 * grants her these keys, so the Admin dashboard calls exactly these routes. A MANAGERIAL user reads them and can
 * neither write an exam nor release one, which is what `lesson.write` and `results.write` say in `permissions.json`.
 */
@RestController
@Tag(name = "Exams", description = "Exam settings, windows, sittings, results and the printable sheet")
@FeatureFlag(FlagKeys.EXAMS)
public class ExamController {
    private final ExamService exams; private final ExamExports exports; private final ExamSheet sheet;
    private final PlatformSettingsService platform;

    public ExamController(ExamService exams, ExamExports exports, ExamSheet sheet, PlatformSettingsService platform) {
        this.exams = exams; this.exports = exports; this.sheet = sheet; this.platform = platform;
    }

    // ---------------------------------------------------------------- settings (§8)

    @GetMapping(value = "/teacher/classes/{classId}/exams", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.read')")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = ExamDto.ExamSettings.class))))
    public List<ExamDto.ExamSettings> classExams(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId) {
        return exams.ofClass(TeacherScope.require(caller), classId);
    }

    @PostMapping(value = "/teacher/classes/{classId}/exams", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    public ExamDto.ExamSettings createExam(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                           @RequestBody @Valid ExamDto.CreateExamRequest body) {
        return exams.create(TeacherScope.require(caller), classId, body);
    }

    @PatchMapping(value = "/teacher/exams/{id}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.write')")
    public ExamDto.ExamSettings updateExam(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                           @RequestBody @Valid ExamDto.UpdateExamRequest body) {
        return exams.update(TeacherScope.require(caller), id, body);
    }

    @PostMapping(value = "/teacher/exams/{id}/publish", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('lesson.publish')")
    public ExamDto.ExamSettings publishExam(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return exams.publish(TeacherScope.require(caller), id);
    }

    // ---------------------------------------------------------------- release and re-opening (§8)

    @PostMapping(value = "/teacher/exams/{id}/release", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.write')")
    public ExamDto.ExamSettings releaseExam(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                            @RequestBody(required = false) @Valid GradingDto.ReleaseRequest body) {
        return exams.release(TeacherScope.require(caller), id, body);
    }

    @PostMapping(value = "/teacher/exams/{id}/reopen/{childId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.write')")
    public ExamDto.ExamReopen reopenExam(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                         @PathVariable String childId) {
        return exams.reopen(TeacherScope.require(caller), id, childId);
    }

    // ---------------------------------------------------------------- results and exports (§8)

    @GetMapping(value = "/teacher/exams/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.read')")
    public ExamDto.ExamResults examResults(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return exams.results(TeacherScope.require(caller), id);
    }

    @GetMapping(value = "/teacher/exams/{id}/results.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> examResultsCsv(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        var results = exams.results(TeacherScope.require(caller), id);
        return file(exports.csv(results), "text/csv; charset=UTF-8", name(results, null) + ".csv");
    }

    @GetMapping(value = "/teacher/exams/{id}/results.xlsx", produces = quest.server.grading.GradingExports.XLSX)
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> examResultsXlsx(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        var results = exams.results(TeacherScope.require(caller), id);
        return file(exports.xlsx(results), quest.server.grading.GradingExports.XLSX, name(results, null) + ".xlsx");
    }

    /**
     * §8's printable sheet. `inline` rather than `attachment`: a teacher printing thirty of these wants the browser's
     * own preview, and `no-store` because the sheet carries one named child's marks.
     */
    @GetMapping(value = "/teacher/exams/{id}/results/{childId}.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> examSheet(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                            @PathVariable String childId) {
        var results = exams.results(TeacherScope.require(caller), id);
        var child = exams.childResult(results, childId);
        var pdf = sheet.render(platform.name(), results, child);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PDF_VALUE)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name(results, child.name()) + ".pdf\"")
                .body(pdf);
    }

    // ---------------------------------------------------------------- downloads

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** A filename a teacher can find again on her desktop, with everything a shell or a browser dislikes removed. */
    private static String name(ExamDto.ExamResults results, String childName) {
        String base = String.join("-", java.util.stream.Stream.of(results.className(), "exam", results.title(), childName)
                .filter(part -> part != null && !part.isBlank()).toList());
        return base.replaceAll("[^A-Za-z0-9\\-_ ]", "").strip().replace(' ', '-').toLowerCase(Locale.ROOT);
    }
}
