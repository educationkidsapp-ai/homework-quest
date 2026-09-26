package quest.server.management;

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
import quest.server.tenancy.ManagerScope;

/**
 * RM1 (DR5): the screens of the manager's area that show the teacher's own numbers — the gradebook, the exams tab, one
 * exam's results and the child page. {@link ManagementReadsController} is {@code CoordinatorReadsController}'s mirror
 * one axis over: the same five bodies, resolved through {@link ManagerScope} and read for <em>every</em> subject of the
 * department rather than for one of them.
 *
 * <p><strong>The flags sit on the handlers, not on the class</strong>, because the routes belong to two different
 * features — `gradebook` (§7) and `exams` (§8) — so a class-level flag could only be wrong for half of them. The
 * teacher's own {@code GradingController} and {@code ExamController} carry exactly these keys, so a manager never sees
 * a screen the school has switched off for the teacher who fills it in. The register carries no flag and therefore
 * lives on {@link ManagementController}, which is already exempt, so that every handler this class declares names one.
 *
 * <p><strong>The keys.</strong> The `management.*` family, split the way `coordinator.*` is: `management.results.read`
 * and `management.exams.read`, so an owner can take exam marks away from managers without closing the whole area.
 * Neither has a `write` sibling — nothing under `/management` writes in RM1.
 */
@RestController
@Tag(name = "Management results", description = "The teacher's gradebook and exam numbers, read through a manager's department")
public class ManagementReadsController {
    private final ManagementService management;

    public ManagementReadsController(ManagementService management) { this.management = management; }

    /** §7's gradebook grid for a section of her department — every subject taught in it. */
    @GetMapping(value = "/management/classes/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.Gradebook managementClassResults(@AuthenticationPrincipal Principals.User caller,
                                                      @PathVariable String id,
                                                      @RequestParam(required = false) String from,
                                                      @RequestParam(required = false) String to) {
        return management.gradebook(ManagerScope.require(caller), id, from, to);
    }

    /** §7's per-lesson results: every child's stops, score and band for one lesson of her department. */
    @GetMapping(value = "/management/lessons/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.LessonResults managementLessonResults(@AuthenticationPrincipal Principals.User caller,
                                                           @PathVariable String id) {
        return management.lessonResults(ManagerScope.require(caller), id);
    }

    /** §7's child page for a child placed in a section of her department: her levels, work, scores and exams. */
    @GetMapping(value = "/management/children/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.results.read')")
    @FeatureFlag(FlagKeys.GRADEBOOK)
    public GradingDto.ChildReport managementChild(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return management.child(ManagerScope.require(caller), id);
    }

    /** §8's Exams tab for a section of her department: a row per exam with its settings, its state and its counts. */
    @GetMapping(value = "/management/classes/{id}/exams", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.exams.read')")
    @FeatureFlag(FlagKeys.EXAMS)
    public List<ExamDto.ExamRow> managementClassExams(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return management.classExams(ManagerScope.require(caller), id);
    }

    /** §8's exam results and band distribution — the exact number `GET /management/stats` estimates in bulk. */
    @GetMapping(value = "/management/exams/{id}/results", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('management.exams.read')")
    @FeatureFlag(FlagKeys.EXAMS)
    public ExamDto.ExamResults managementExamResults(@AuthenticationPrincipal Principals.User caller, @PathVariable String id) {
        return management.examResults(ManagerScope.require(caller), id);
    }
}
