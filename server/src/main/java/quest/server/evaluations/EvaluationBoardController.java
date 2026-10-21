package quest.server.evaluations;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import quest.server.auth.Principals;
import quest.server.flags.FeatureFlag;
import quest.server.flags.FlagKeys;

/**
 * Feature 33: the leaders' "Evaluation tracking / متابعة التقييم" page — the round's board, one teacher's own
 * evaluations, Approve or Return, and the CSV of the counts.
 *
 * <p>`evaluations.board` is ADMIN, MANAGERIAL, COORDINATOR, FLOOR_SUPERVISOR and TEACHER: anyone who may be named a
 * direct manager, exactly as `attendance.board` and `materials.board` are. What each of them sees is
 * {@link quest.server.staffattendance.StaffBoardScope}'s answer — feature 17's own scope — and not the permission's.
 * Behind `childEvaluations`.
 */
@RestController
@FeatureFlag(FlagKeys.CHILD_EVALUATIONS)
@Tag(name = "Child evaluation board", description = "Who has evaluated her children, and the review of what she wrote")
public class EvaluationBoardController {
    private final ChildEvaluationService evaluations;

    public EvaluationBoardController(ChildEvaluationService evaluations) { this.evaluations = evaluations; }

    @GetMapping(value = "/evaluations/board", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.board')")
    public EvaluationDto.EvaluationBoard evaluationBoard(@AuthenticationPrincipal Principals.User caller,
                                                         @RequestParam(required = false) String round) {
        return evaluations.board(caller, round);
    }

    @GetMapping(value = "/evaluations/board.csv", produces = "text/csv")
    @PreAuthorize("@permit.has('evaluations.board')")
    public ResponseEntity<byte[]> evaluationBoardCsv(@AuthenticationPrincipal Principals.User caller,
                                                     @RequestParam(required = false) String round) {
        var board = evaluations.board(caller, round);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + EvaluationExport.filename(board) + "\"")
                .body(EvaluationExport.csv(board));
    }

    @GetMapping(value = "/evaluations/board/{teacherId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.board')")
    public EvaluationDto.EvaluationBoard teacherEvaluationBoard(@AuthenticationPrincipal Principals.User caller,
                                                                @PathVariable String teacherId,
                                                                @RequestParam(required = false) String round) {
        return evaluations.boardOf(caller, teacherId, round);
    }

    @PostMapping(value = "/evaluations/{id}/review", consumes = MediaType.APPLICATION_JSON_VALUE,
                 produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@permit.has('evaluations.board')")
    public EvaluationDto.ChildEvaluationRow reviewEvaluation(@AuthenticationPrincipal Principals.User caller,
                                                             @PathVariable String id,
                                                             @RequestBody EvaluationDto.ReviewEvaluationRequest body) {
        return evaluations.review(caller, id, body);
    }
}
