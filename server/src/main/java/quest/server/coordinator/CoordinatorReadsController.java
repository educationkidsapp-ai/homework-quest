package quest.server.coordinator;

import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.exams.ExamDto;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;
import quest.server.grading.GradingDto;
import quest.server.tenancy.CoordinatorScope;

/**
 * R3 (`docs/plan.md` phase R, DR2): the four screens of the coordinator's area that show the teacher's own numbers —
 * attendance, the gradebook, the exams tab and the child page. Read-only, like the rest of `/coordinator/**`, and
 * every body here is the very body the teacher's route answers, because {@link CoordinatorReadsService} delegates
 * rather than recomputes.
 *
 * <p><strong>The flags sit on the handlers, not on the class.</strong> The routes belong to two different features —
 * `gradebook` (§7) and `exams` (§8) — so a class-level flag could only be wrong for half of them; a school that has
 * the gradebook off gets 404 from the three results routes and still reads its exams. The teacher's own
 * {@code GradingController} and {@code ExamController} carry exactly these keys, so a coordinator never sees a screen
 * the school has switched off for the teacher who fills it in.
 *
 * <p><strong>Attendance carries no flag</strong>, for the reason {@code AttendanceController} carries none and sits in
 * `FeatureFlagCoverageTest.INFRASTRUCTURE`: there is no `attendance` key in {@link FlagKeys} to name. Taking register
 * is not an optional feature of this product, and inventing a flag here would gate the coordinator's half of a
 * feature the teacher's half is not gated by. Its handler therefore lives on {@link CoordinatorController}, which is
 * already exempt, so that this class can keep the rule that <em>every</em> handler it declares names a flag.
 *
 * <p><strong>The keys.</strong> R2 gave the namespace its own family rather than lending the teacher's — a COORDINATOR
 * holds no `results.read` — and split `coordinator.lesson.read` off `coordinator.read` on the first read that was not
 * the Home screen. R3 keeps that split: `coordinator.results.read` and `coordinator.exams.read`, so an owner can take
 * exam marks away from coordinators without closing the whole area, and neither key has a `write` sibling.
 */
@RestController
@Tag(name = "Coordinator results", description = "The teacher's attendance, gradebook and exam numbers, read through a coordinator's scope")
public class CoordinatorReadsController {
    private final CoordinatorReadsService reads;

    public CoordinatorReadsController(CoordinatorReadsService reads) { this.reads = reads; }

    /** §7's gradebook grid for a section in scope — the same children × lessons body `/teacher/…/gradebook` answers. */
    @GetMapping(value = "/coordinator/classes/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.Gradebook coordinatorClassResults(@AuthenticationPrincipal Principals.User caller,
                                                       @PathVariable String id,
                                                       @RequestParam(required = false) String from,
                                                       @RequestParam(required = false) String to) {
        return reads.gradebook(CoordinatorScope.require(caller), id, from, to);
    }

    /** §7's per-lesson results: every child's stops, score and band for one lesson of her subject. */
    @GetMapping(value = "/coordinator/lessons/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.LessonResults coordinatorLessonResults(@AuthenticationPrincipal Principals.User caller,
                                                            @PathVariable String id) {
        return reads.lessonResults(CoordinatorScope.require(caller), id);
    }

    /** §7's child page for a child placed in a section she supervises: her levels, work, released scores and exams. */
    @GetMapping(value = "/coordinator/children/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.ChildReport coordinatorChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return reads.child(CoordinatorScope.require(caller), id);
    }

    /** §8's Exams tab for a section in scope: a row per exam with its settings, its state and its three counts. */
    @GetMapping(value = "/coordinator/classes/{id}/exams", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.exams.read')")
    @FeatureFlag(FlagKeys.EXAMS)
    public List<ExamDto.ExamRow> coordinatorClassExams(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return reads.classExams(CoordinatorScope.require(caller), id);
    }

    /** §8's exam results and band distribution, for an exam of her own subject and track. */
    @GetMapping(value = "/coordinator/exams/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('coordinator.exams.read')")
    @FeatureFlag(FlagKeys.EXAMS)
    public ExamDto.ExamResults coordinatorExamResults(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return reads.examResults(CoordinatorScope.require(caller), id);
    }
}
