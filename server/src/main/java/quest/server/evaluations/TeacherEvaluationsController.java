package quest.server.evaluations;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
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

/**
 * Feature 33: the teacher's own "Evaluations / تقييم الطلاب" page (`quest.api.dashboard.ChildEvaluations`).
 *
 * <p>`evaluations.self` is TEACHER and nobody else: the rows are her assignments' children, the words are hers, and
 * a parent or a child never reaches any of it. Behind `childEvaluations`, so a school that does not evaluate never
 * sees the routes (404).
 */
@RestController
@FeatureFlag(FlagKeys.CHILD_EVALUATIONS)
@Tag(name = "Child evaluations", description = "A teacher's evaluation of each child she teaches, per round")
public class TeacherEvaluationsController {
    private final ChildEvaluationService evaluations;

    public TeacherEvaluationsController(ChildEvaluationService evaluations) { this.evaluations = evaluations; }

    @GetMapping(value = "/teacher/evaluations", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.self')")
    public EvaluationDto.TeacherEvaluations myEvaluations(@AuthenticationPrincipal Principals.User caller,
                                                          @RequestParam(required = false) String round) {
        return evaluations.mine(caller, round);
    }

    @GetMapping(value = "/teacher/evaluations/{roundId}/{classId}/{subject}/{childId}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.self')")
    public EvaluationDto.ChildEvaluationRow myEvaluation(@AuthenticationPrincipal Principals.User caller,
                                                         @PathVariable String roundId, @PathVariable String classId,
                                                         @PathVariable String subject, @PathVariable String childId) {
        return evaluations.one(caller, roundId, classId, subject, childId);
    }

    @PutMapping(value = "/teacher/evaluations/{roundId}/{classId}/{subject}/{childId}",
                consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.self')")
    public EvaluationDto.ChildEvaluationRow saveEvaluation(@AuthenticationPrincipal Principals.User caller,
                                                           @PathVariable String roundId, @PathVariable String classId,
                                                           @PathVariable String subject, @PathVariable String childId,
                                                           @RequestBody EvaluationDto.SaveChildEvaluationRequest body) {
        return evaluations.save(caller, roundId, classId, subject, childId, body);
    }

    @PostMapping(value = "/teacher/evaluations/{roundId}/{classId}/{subject}/{childId}/submit",
                 consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.self')")
    public EvaluationDto.ChildEvaluationRow submitEvaluation(@AuthenticationPrincipal Principals.User caller,
                                                             @PathVariable String roundId, @PathVariable String classId,
                                                             @PathVariable String subject, @PathVariable String childId,
                                                             @RequestBody(required = false) EvaluationDto.SaveChildEvaluationRequest body) {
        return evaluations.submit(caller, roundId, classId, subject, childId, body);
    }
}
