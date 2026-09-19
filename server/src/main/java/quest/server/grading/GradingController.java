package quest.server.grading;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.tenancy.TeacherScope;

/**
 * `docs/teacher-flow.md` step 9 — results, marking, release, gradebook and the child page.
 *
 * <p><strong>The flags.</strong> The class carries `gradebook` (§9 of the flow), so a school that has not switched
 * the feature on gets 404 for every route here rather than an empty grid. `PUT /teacher/marks` carries
 * `openStopMarking` on the handler instead: §9 lists them as two features, and a school may want the grid of
 * automatic scores without asking its teachers to mark retells by hand.
 *
 * <p><strong>Scope, and the Admin.</strong> Every handler resolves the lesson, class or child it names through
 * {@link TeacherScope} inside {@link GradingService} — another school's is a 404, another teacher's is a 403. There
 * is no separate `/admin/**` alias: `TeacherScope` already lets an ADMIN reach any class of the school she picked
 * with `X-School-Id`, and `permissions.json` grants her these keys, so the Admin dashboard calls exactly these
 * routes. A MANAGERIAL user reads them and cannot mark or release, which is `results.write` in `permissions.json`.
 */
@RestController
@Tag(name = "Results and gradebook", description = "Scores, teacher marks, release, the class gradebook and the child page")
@FeatureFlag(FlagKeys.GRADEBOOK)
public class GradingController {
    private final GradingService grading; private final GradingExports exports;

    public GradingController(GradingService grading, GradingExports exports) { this.grading = grading; this.exports = exports; }

    // ---------------------------------------------------------------- results (§7)

    @GetMapping(value = "/teacher/lessons/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.read')")
    public GradingDto.LessonResults lessonResults(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return grading.results(TeacherScope.require(caller), id);
    }

    @GetMapping(value = "/teacher/lessons/{id}/results.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> lessonResultsCsv(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        var results = grading.results(TeacherScope.require(caller), id);
        return file(exports.resultsCsv(results), "text/csv; charset=UTF-8", name(results.className(), results.title(), "results") + ".csv");
    }

    @GetMapping(value = "/teacher/lessons/{id}/results.xlsx", produces = GradingExports.XLSX)
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> lessonResultsXlsx(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        var results = grading.results(TeacherScope.require(caller), id);
        return file(exports.resultsXlsx(results), GradingExports.XLSX, name(results.className(), results.title(), "results") + ".xlsx");
    }

    // ---------------------------------------------------------------- marking and release (§7)

    /**
     * `openStopMarking` rather than the class's `gradebook`: §9 lists them separately, and marking is the half a
     * school can be without. A school with neither gets 404 from the class-level flag first.
     */
    @PutMapping(value = "/teacher/marks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.write')")
    @FeatureFlag(FlagKeys.OPEN_STOP_MARKING)
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = GradingDto.TeacherMark.class))))
    public List<GradingDto.TeacherMark> saveMarks(@AuthenticationPrincipal Principals.User caller,
                                                  @RequestBody @Valid GradingDto.SaveMarksRequest body) {
        return grading.saveMarks(TeacherScope.require(caller), body);
    }

    @PostMapping(value = "/teacher/lessons/{id}/release", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.write')")
    public GradingDto.LessonRelease releaseLesson(@AuthenticationPrincipal Principals.User caller, @PathVariable String id,
                                                  @RequestBody(required = false) @Valid GradingDto.ReleaseRequest body) {
        return grading.release(TeacherScope.require(caller), id, body);
    }

    // ---------------------------------------------------------------- the gradebook and the child page (§7)

    @GetMapping(value = "/teacher/classes/{classId}/gradebook", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.read')")
    public GradingDto.Gradebook gradebook(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                          @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return grading.gradebook(TeacherScope.require(caller), classId, from, to);
    }

    @GetMapping(value = "/teacher/classes/{classId}/gradebook.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> gradebookCsv(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                               @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        var book = grading.gradebook(TeacherScope.require(caller), classId, from, to);
        return file(exports.gradebookCsv(book), "text/csv; charset=UTF-8", name(book.className(), null, "gradebook") + ".csv");
    }

    @GetMapping(value = "/teacher/classes/{classId}/gradebook.xlsx", produces = GradingExports.XLSX)
    @PreAuthorize("@permit.has('results.read')")
    public ResponseEntity<byte[]> gradebookXlsx(@AuthenticationPrincipal Principals.User caller, @PathVariable String classId,
                                                @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        var book = grading.gradebook(TeacherScope.require(caller), classId, from, to);
        return file(exports.gradebookXlsx(book), GradingExports.XLSX, name(book.className(), null, "gradebook") + ".xlsx");
    }

    @GetMapping(value = "/teacher/children/{childId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('results.read')")
    public GradingDto.ChildReport childReport(@AuthenticationPrincipal Principals.User caller, @PathVariable String childId) {
        return grading.child(TeacherScope.require(caller), childId);
    }

    // ---------------------------------------------------------------- downloads

    private static ResponseEntity<byte[]> file(byte[] body, String contentType, String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /** A filename a teacher can find again on her desktop, with everything a shell or a browser dislikes removed. */
    private static String name(String className, String title, String kind) {
        String base = (className == null ? kind : className + "-" + kind) + (title == null ? "" : "-" + title);
        return base.replaceAll("[^A-Za-z0-9\\-_ ]", "").strip().replace(' ', '-').toLowerCase(java.util.Locale.ROOT);
    }
}
